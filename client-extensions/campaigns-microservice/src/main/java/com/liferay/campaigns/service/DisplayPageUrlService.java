// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves Display Page URLs dynamically by querying the Liferay site and
 * object-definition APIs, mirroring the approach used in the cmschat
 * microservice.  Results are cached for the lifetime of the application
 * since site friendly-URL paths and object-definition separators rarely
 * change.
 *
 * <p>Supports both custom Object types (actions URLs containing
 * {@code /o/c/}) and standard Liferay content types (actions URLs
 * containing {@code /o/headless-delivery/}).</p>
 *
 * <p>Unlike the cmschat original (which uses a dedicated {@code LiferayClient}
 * with separate JWT and basic-auth methods), this port uses {@link RestTemplate}
 * and forwards the {@link HttpHeaders} already supplied by the caller.  When no
 * headers are supplied (startup load), it builds Basic auth from the configured
 * {@code liferay.email} / {@code liferay.password} credentials.</p>
 *
 * @author Neil Griffin
 */
@Service
public class DisplayPageUrlService {

	@PostConstruct
	public void init() {
		try {
			_loadAllObjectDefinitions(null);
		}
		catch (Exception e) {
			_log.warn(
				"Failed to load object definitions at startup (will retry on " +
					"first request): {}",
				e.getMessage());
		}

		try {
			_loadAllSites(null);
		}
		catch (Exception e) {
			_log.warn(
				"Failed to load sites at startup (will retry on first " +
					"request): {}",
				e.getMessage());
		}

		try {
			_loadAllAssetLibraries(null);
		}
		catch (Exception e) {
			_log.warn(
				"Failed to load asset libraries at startup (will retry on " +
					"first request): {}",
				e.getMessage());
		}
	}

	/**
	 * Resolves a Display Page URL from the JSON node of an object entry.
	 * Handles both custom Object types and standard Liferay content types.
	 *
	 * @param  entryJsonNode the headless entry representation (must include the
	 *         {@code actions} node, {@code scopeKey}/{@code siteId}, and
	 *         {@code friendlyUrlPath}/{@code id})
	 * @param  authHeaders the headers to forward to Liferay (Bearer or Basic);
	 *         may be {@code null} to use the configured admin credentials
	 * @param  siteKey an optional explicit rendering-site identifier; campaigns
	 *         passes {@code null} (no fragment context)
	 * @return the resolved Display Page URL path (e.g.
	 *         {@code /web/sales-portal/press-release/my-article}), or
	 *         {@code null} if resolution fails
	 */
	public String resolveUrl(
		JsonNode entryJsonNode, HttpHeaders authHeaders, String siteKey) {

		try {
			JsonNode actionsNode = entryJsonNode.path("actions");

			// Try custom object resolution first (/o/c/ and /o/cms/ URLs)

			String objectRestPath = _extractObjectRestPath(actionsNode);

			if (objectRestPath != null) {
				return _resolveCustomObjectUrl(
					entryJsonNode, objectRestPath, authHeaders, siteKey);
			}

			// Fall back to standard content type resolution
			// (/o/headless-delivery/ URLs)

			String contentType = _extractHeadlessDeliveryType(actionsNode);

			if (contentType != null) {
				return _resolveStandardTypeUrl(
					entryJsonNode, contentType, authHeaders);
			}

			_log.debug("Could not determine content type from actions URLs");

			return null;
		}
		catch (Exception e) {
			_log.warn("Failed to resolve Display Page URL: {}", e.getMessage());

			return null;
		}
	}

	// --- URL Building ---

	private String _buildUrl(
		JsonNode entryJsonNode, String scopeKey, String separator,
		HttpHeaders authHeaders) {

		String sitePath = _resolveSiteFriendlyUrl(scopeKey, authHeaders);

		if (sitePath == null) {
			return null;
		}

		String entryFriendlyUrl = entryJsonNode.path(
			"friendlyUrlPath").asText("");

		String scopeType = entryJsonNode.path("systemProperties").path(
			"scope").path("type").asText("");

		if ("AssetLibrary".equals(scopeType)) {

			// Resolve the Asset Library group id from the cache using the Asset
			// Library name from the entry JSON's scopeKey (e.g. "Corporate").
			// The scopeKey parameter passed to this method is the RENDERING
			// site identifier (e.g. "44217"), which is correct for
			// _resolveSiteFriendlyUrl above but cannot be used here. The asset
			// library cache is keyed by Asset Library name, not rendering site
			// id.

			String assetLibraryScopeKey = entryJsonNode.path(
				"scopeKey").asText("");
			String assetLibraryGroupId = _resolveAssetLibraryGroupId(
				assetLibraryScopeKey, authHeaders);

			String urlTitle = entryFriendlyUrl.isBlank() ?
				entryJsonNode.path("id").asText("") : entryFriendlyUrl;

			if ((assetLibraryGroupId != null) && !urlTitle.isBlank()) {
				entryFriendlyUrl =
					"asset-library-" + assetLibraryGroupId + "/" + urlTitle;
			}
		}

		if (entryFriendlyUrl.isBlank()) {
			String id = entryJsonNode.path("id").asText("");

			if (id.isBlank()) {
				_log.debug(
					"No friendlyUrlPath or id available for URL resolution");

				return null;
			}

			entryFriendlyUrl = id;
		}

		String url = "/web" + sitePath + "/" + separator + "/" +
			entryFriendlyUrl;

		_log.debug("Resolved Display Page URL: {}", url);

		return url;
	}

	// --- Content Type Extraction ---

	private String _extractHeadlessDeliveryType(JsonNode actionsNode) {
		if (actionsNode.isObject()) {
			java.util.Iterator<JsonNode> elements = actionsNode.elements();

			while (elements.hasNext()) {
				JsonNode actionNode = elements.next();
				String href = actionNode.path("href").asText("");

				if (!href.isBlank()) {
					Matcher matcher = _HEADLESS_DELIVERY_PATTERN.matcher(href);

					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			}
		}

		return null;
	}

	private String _extractObjectRestPath(JsonNode actionsNode) {
		if (actionsNode.isObject()) {
			java.util.Iterator<JsonNode> elements = actionsNode.elements();

			while (elements.hasNext()) {
				JsonNode actionNode = elements.next();
				String href = actionNode.path("href").asText("");

				if (!href.isBlank()) {
					Matcher matcher = _OBJECT_REST_PATH_PATTERN.matcher(href);

					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			}
		}

		return null;
	}

	/**
	 * Performs a GET against the Liferay headless API.  When {@code authHeaders}
	 * is non-null, those headers are forwarded (the caller's Bearer or Basic
	 * auth).  When it is null (startup load), Basic auth is built from the
	 * configured admin credentials.
	 */
	private String _get(String relativePath, HttpHeaders authHeaders)
		throws Exception {

		HttpHeaders headers = new HttpHeaders();

		if (authHeaders != null) {
			headers.putAll(authHeaders);
		}
		else {
			String credentials = _liferayEmail + ":" + _liferayPassword;

			String encodedCredentials = Base64.getEncoder().encodeToString(
				credentials.getBytes(StandardCharsets.UTF_8));

			headers.set("Authorization", "Basic " + encodedCredentials);
		}

		headers.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<String> entity = new HttpEntity<>(headers);

		ResponseEntity<String> response = _restTemplate.exchange(
			_liferayHeadlessApiBaseUrl + relativePath, HttpMethod.GET, entity,
			String.class);

		return response.getBody();
	}

	// --- Custom Object URL Resolution ---

	private String _resolveAssetLibraryGroupId(
		String scopeKey, HttpHeaders authHeaders) {

		String key = (scopeKey != null) ? scopeKey.toLowerCase() : "";
		String cached = _assetLibraryCache.get(key);

		if (cached != null) {
			return cached;
		}

		if (!_assetLibrariesLoaded.get() &&
			!_assetLibrariesAuthAttempted.get()) {

			_loadAllAssetLibraries(authHeaders);
		}

		return _assetLibraryCache.get(key);
	}

	private String _resolveCustomObjectSeparator(
		String objectRestPath, HttpHeaders authHeaders) {

		String cached = _separatorCache.get(objectRestPath);

		if (cached != null) {
			return cached;
		}

		// Fallback: startup load may have failed, retry once with the request's
		// auth

		if (!_objectDefinitionsLoaded.get() &&
			!_objectDefinitionsAuthAttempted.get()) {

			_loadAllObjectDefinitions(authHeaders);
		}

		return _separatorCache.get(objectRestPath);
	}

	private String _resolveCustomObjectUrl(
		JsonNode entryJsonNode, String objectRestPath, HttpHeaders authHeaders,
		String siteKey) {

		String scopeType = entryJsonNode.path("systemProperties").path(
			"scope").path("type").asText("");
		String scopeKey = entryJsonNode.path("scopeKey").asText("");

		if (scopeKey.isBlank()) {
			_log.debug("Could not extract scopeKey from entry JSON");

			return null;
		}

		// For Asset Library content, the scopeKey is the Asset Library name
		// (e.g. "Corporate"), which is not in the site cache. The
		// caller-supplied siteKey is the reliable rendering site. Fall back to
		// the heuristic only when no siteKey is provided.

		String siteScopeKey;

		if ("AssetLibrary".equals(scopeType)) {
			if ((siteKey != null) && !siteKey.isBlank()) {
				siteScopeKey = siteKey;
			}
			else {
				siteScopeKey = _resolveRenderingSiteKey(authHeaders);
			}
		}
		else {
			siteScopeKey = scopeKey;
		}

		if (siteScopeKey == null) {
			_log.debug(
				"Could not determine rendering site for Asset Library " +
					"scopeKey '{}'",
				scopeKey);

			return null;
		}

		String separator = _resolveCustomObjectSeparator(
			objectRestPath, authHeaders);

		if (separator == null) {
			return null;
		}

		return _buildUrl(entryJsonNode, siteScopeKey, separator, authHeaders);
	}

	/**
	 * Resolves the scope key of the first real (non-Global) site to use as the
	 * base URL for Asset Library content.  Asset Library content is rendered in
	 * the context of a site that has connected the library, so any connected
	 * site is a reasonable default.
	 */
	private String _resolveRenderingSiteKey(HttpHeaders authHeaders) {
		if (!_sitesLoaded.get()) {
			_reloadSites(authHeaders);
		}

		// Return the first site that isn't Global or Guest (those are
		// platform-level)

		for (Map.Entry<String, String> entry : _siteCache.entrySet()) {
			String name = entry.getKey();

			if (!name.equals("global") && !name.equals("guest") &&
				!name.matches("\\d+")) {

				return name;
			}
		}

		return null;
	}

	// --- Standard Content Type URL Resolution ---

	private String _resolveSiteFriendlyUrl(
		String scopeKey, HttpHeaders authHeaders) {

		String key = (scopeKey != null) ? scopeKey.toLowerCase() : "";
		String cached = _siteCache.get(key);

		if (cached != null) {
			return cached;
		}

		// Cache miss — reload sites (a new site may have been added)

		_reloadSites(authHeaders);

		return _siteCache.get(key);
	}

	private String _resolveStandardTypeUrl(
		JsonNode entryJsonNode, String contentType, HttpHeaders authHeaders) {

		String separator = _STANDARD_SEPARATORS.get(contentType);

		if (separator == null) {
			_log.debug(
				"No known separator for standard content type '{}'",
				contentType);

			return null;
		}

		// Standard types carry siteId directly in the entry JSON

		String siteId = entryJsonNode.path("siteId").asText("");

		if (siteId.isBlank()) {
			_log.debug(
				"No siteId found in entry JSON for standard type '{}'",
				contentType);

			return null;
		}

		return _buildUrl(entryJsonNode, siteId, separator, authHeaders);
	}

	// --- Site Resolution ---

	private synchronized void _reloadSites(HttpHeaders authHeaders) {
		if (authHeaders != null) {

			// Request-time reload with forwarded auth — always allowed (new
			// sites)

			_loadAllSites(authHeaders);
		}
		else if (!_sitesLoaded.get() && !_sitesAuthAttempted.get()) {

			// Startup retry only if never succeeded and request auth not yet
			// tried

			_loadAllSites(null);
		}
	}

	private synchronized void _loadAllSites(HttpHeaders authHeaders) {
		try {
			int page = 1;
			int loadedCount = 0;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString("/headless-admin-site/v1.0/sites")
					.queryParam("fields", "name,friendlyUrlPath,id")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = _get(url, authHeaders);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String name = item.path("name").asText("");
					String friendlyUrlPath = item.path(
						"friendlyUrlPath").asText("");
					String id = item.path("id").asText("");

					if (!friendlyUrlPath.isBlank()) {

						// Key by name (matches scopeKey from custom objects)
						// Lowercase to ensure case-insensitive matching

						if (!name.isBlank()) {
							_siteCache.put(name.toLowerCase(), friendlyUrlPath);
						}

						// Key by numeric id (matches siteId from standard
						// content)

						if (!id.isBlank()) {
							_siteCache.put(id, friendlyUrlPath);
						}

						loadedCount++;

						_log.debug(
							"Cached site '{}' (id={}) → '{}'", name, id,
							friendlyUrlPath);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_sitesLoaded.set(true);

			if (authHeaders != null) {
				_sitesAuthAttempted.set(true);
			}

			_log.info("Loaded {} sites into cache", loadedCount);
		}
		catch (Exception e) {
			if (authHeaders != null) {
				_sitesAuthAttempted.set(true);
			}

			_log.warn("Failed to load sites: {}", e.getMessage());
		}
	}

	// --- Asset Library Loading ---

	private synchronized void _loadAllAssetLibraries(HttpHeaders authHeaders) {
		if (_assetLibrariesLoaded.get()) {
			return;
		}

		if ((authHeaders != null) &&
			!_assetLibrariesAuthAttempted.compareAndSet(false, true)) {

			return;
		}

		try {
			int page = 1;
			int loadedCount = 0;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString(
						"/headless-asset-library/v1.0/asset-libraries")
					.queryParam("fields", "id,name,assetLibraryKey")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = _get(url, authHeaders);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String id = item.path("id").asText("");
					String name = item.path("name").asText("");
					String assetLibraryKey = item.path(
						"assetLibraryKey").asText("");

					if (!id.isBlank()) {

						// Key by lowercase name (matches scopeKey from CMS
						// Object search results)

						if (!name.isBlank()) {
							_assetLibraryCache.put(name.toLowerCase(), id);
						}

						// Key by assetLibraryKey (may differ from name in some
						// configurations)

						if (!assetLibraryKey.isBlank() &&
							!assetLibraryKey.equalsIgnoreCase(name)) {

							_assetLibraryCache.put(
								assetLibraryKey.toLowerCase(), id);
						}

						// Key by numeric id for direct lookups

						_assetLibraryCache.put(id, id);

						loadedCount++;

						_log.debug(
							"Cached asset library '{}' (key='{}') → id={}", name,
							assetLibraryKey, id);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_assetLibrariesLoaded.set(true);

			_log.info("Loaded {} asset libraries into cache", loadedCount);
		}
		catch (Exception e) {
			_log.warn("Failed to load asset libraries: {}", e.getMessage());
		}
	}

	// --- Object Definitions Loading ---

	private synchronized void _loadAllObjectDefinitions(
		HttpHeaders authHeaders) {

		if (_objectDefinitionsLoaded.get()) {
			return;
		}

		// If called with request auth, only allow one attempt

		if ((authHeaders != null) &&
			!_objectDefinitionsAuthAttempted.compareAndSet(false, true)) {

			return;
		}

		try {
			int page = 1;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString("/object-admin/v1.0/object-definitions")
					.queryParam(
						"fields", "restContextPath,friendlyURLSeparator")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = _get(url, authHeaders);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String restContextPath = item.path(
						"restContextPath").asText("");
					String separator = item.path(
						"friendlyURLSeparator").asText("");

					if (separator.isBlank()) {
						continue;
					}

					String key = null;

					if (restContextPath.startsWith("/o/c/")) {
						key = restContextPath.substring("/o/c/".length());
					}
					else if (restContextPath.startsWith("/o/cms/")) {
						key = restContextPath.substring("/o/cms/".length());
					}

					if (key != null) {
						_separatorCache.putIfAbsent(key, separator);

						_log.debug(
							"Cached separator for '{}': '{}'", key, separator);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_objectDefinitionsLoaded.set(true);

			_log.info(
				"Loaded {} custom object definition separators",
				_separatorCache.size());
		}
		catch (Exception e) {
			_log.warn("Failed to load object definitions: {}", e.getMessage());
		}
	}

	private static final Logger _log = LoggerFactory.getLogger(
		DisplayPageUrlService.class);

	private static final ObjectMapper _objectMapper = new ObjectMapper();

	/**
	 * Pattern to extract the REST context path from a custom object or CMS
	 * object actions URL.  Matches both {@code /o/c/pressreleases/12345} and
	 * {@code /o/cms/blogs/12345}.
	 */
	private static final Pattern _OBJECT_REST_PATH_PATTERN = Pattern.compile(
		"/o/(?:c|cms)/([^/]+)");

	/**
	 * Pattern to extract the content type from a headless-delivery actions URL
	 * like {@code /o/headless-delivery/v1.0/structured-contents/12345}.
	 */
	private static final Pattern _HEADLESS_DELIVERY_PATTERN = Pattern.compile(
		"/o/headless-delivery/v[^/]+/(?:sites/[^/]+/)?([^/]+)");

	/**
	 * Standard Liferay content type separators used in Display Page URLs.
	 * These are Liferay DXP platform conventions for built-in content types.
	 */
	private static final Map<String, String> _STANDARD_SEPARATORS = Map.of(
		"structured-contents", "w",
		"blog-postings", "b",
		"documents", "d");

	// --- Object Definitions Cache ---

	/** Tracks whether the full object-definition scan has completed. */
	private final AtomicBoolean _objectDefinitionsLoaded = new AtomicBoolean(
		false);

	/** Tracks whether the request-auth fallback load has been attempted. */
	private final AtomicBoolean _objectDefinitionsAuthAttempted =
		new AtomicBoolean(false);

	/**
	 * Cache: REST path key (e.g. "forumreplies") → friendlyURLSeparator (e.g.
	 * "c_forumreply").
	 */
	private final Map<String, String> _separatorCache =
		new ConcurrentHashMap<>();

	// --- Sites Cache ---

	/** Tracks whether the initial site scan has completed successfully. */
	private final AtomicBoolean _sitesLoaded = new AtomicBoolean(false);

	/** Tracks whether the request-auth fallback load for sites was attempted. */
	private final AtomicBoolean _sitesAuthAttempted = new AtomicBoolean(false);

	/**
	 * Cache: site identifier → friendlyUrlPath.  Keyed by both the site
	 * {@code name} (which matches the {@code scopeKey} field on custom object
	 * search results) and the site {@code id} (which matches the {@code siteId}
	 * field on standard content search results).
	 */
	private final Map<String, String> _siteCache = new ConcurrentHashMap<>();

	// --- Asset Library Cache ---

	/** Tracks whether the initial asset library scan has completed. */
	private final AtomicBoolean _assetLibrariesLoaded = new AtomicBoolean(
		false);

	/**
	 * Tracks whether the request-auth fallback load for asset libraries was
	 * attempted.
	 */
	private final AtomicBoolean _assetLibrariesAuthAttempted =
		new AtomicBoolean(false);

	/**
	 * Cache: Asset Library identifier → Asset Library group id (used in the
	 * {@code asset-library-{id}} URL segment).  Keyed by both the
	 * {@code name}/{@code assetLibraryKey} (which matches the {@code scopeKey}
	 * field on CMS Object search results) and the numeric {@code id}.
	 */
	private final Map<String, String> _assetLibraryCache =
		new ConcurrentHashMap<>();

	@Autowired
	private RestTemplate _restTemplate;

	@Value("${liferay.email:}")
	private String _liferayEmail;

	@Value("${liferay.headless.api.base.url:http://localhost:8080/o}")
	private String _liferayHeadlessApiBaseUrl;

	@Value("${liferay.password:}")
	private String _liferayPassword;

}
