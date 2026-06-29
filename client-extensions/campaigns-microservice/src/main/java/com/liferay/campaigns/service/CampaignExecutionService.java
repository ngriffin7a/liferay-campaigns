// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.service;

import com.liferay.campaigns.dto.Recipient;
import com.liferay.campaigns.util.HtmlUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class CampaignExecutionService {

	/**
	 * Sends a campaign identified only by its id. The campaign record (content
	 * reference, recipients, and workflow status) is fetched from Liferay using
	 * the caller's {@code authHeaders} (their JWT), so Liferay -- not this
	 * service -- stays the source of truth: it enforces that the caller may see
	 * the campaign, and the recipients and content are always the real, stored
	 * ones rather than anything the caller supplies. The campaign must be in the
	 * Approved state, re-checked server-side rather than trusting what the
	 * browser only hides in the UI.
	 */
	public String executeCampaignById(
		HttpHeaders authHeaders, String campaignId) {

		// The id is interpolated into the headless API URL path, so accept only
		// well-formed numeric ids and reject anything else -- keeping the
		// outbound fetch URL clean (no path/query injection).

		if (_isBlank(campaignId) || !_isNumericId(campaignId.trim())) {
			_log.warn(
				"Rejecting send request for invalid campaignId: {}",
				campaignId);

			return "Invalid campaignId";
		}

		JsonNode campaign;

		try {
			HttpHeaders headers = new HttpHeaders();

			headers.putAll(authHeaders);
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<String> entity = new HttpEntity<>(headers);

			String url =
				_liferayHeadlessApiBaseUrl + "/c/campaigns/" +
					campaignId.trim();

			if (_log.isDebugEnabled()) {
				_log.debug("Fetching campaign for send from: {}", url);
			}

			ResponseEntity<String> response = _restTemplate.exchange(
				url, HttpMethod.GET, entity, String.class);

			campaign = new ObjectMapper().readTree(response.getBody());
		}
		catch (HttpClientErrorException e) {

			// 403/404 mean the caller may not see this campaign (or it does not
			// exist). Liferay is the authorization gate here; don't distinguish
			// the two cases back to the caller.

			_log.warn(
				"Unable to fetch campaign id={} for send: {}", campaignId,
				e.getStatusCode());

			return "Campaign not found or access denied";
		}
		catch (Exception e) {
			_log.error(
				"Failed to fetch campaign id={} for send", campaignId, e);

			return "Failed to fetch campaign";
		}

		if (!isApproved(campaign)) {
			_log.warn(
				"Refusing to send campaign id={}: not in Approved state",
				campaignId);

			return "Campaign is not approved";
		}

		// Send-once guard: once a campaign has been sent its lastSentDate is
		// stamped (see _stampLastSent), and a repeat send is refused so a reader
		// of an approved campaign cannot re-blast its recipients. An
		// administrator clears lastSentDate (via the "Clear Last Sent" row
		// action) to deliberately send again.

		if (isAlreadySent(campaign)) {
			String lastSentDate = campaign.path("lastSentDate").asText("");

			_log.warn(
				"Refusing to re-send campaign id={}: already sent on {}",
				campaignId, lastSentDate);

			return "Campaign was already sent on " + lastSentDate +
				". Clear Last Sent to send it again.";
		}

		// Every value driving the send is read from the fetched record, never
		// from the HTTP caller. This is the trust boundary: the caller chooses
		// only which (readable, approved) campaign to send.

		return executeCampaign(
			authHeaders, campaign.path("id").asText(null),
			campaign.path("title").asText(null),
			campaign.path("objectDefinitionId").asText(null),
			campaign.path("objectDefinitionTitle").asText(null),
			campaign.path("objectEntryId").asText(null),
			campaign.path("recipientUserGroupIds").asText(null),
			campaign.path("recipientUserIds").asText(null),
			campaign.path("recipientEmailAddresses").asText(null));
	}

	/**
	 * Sends a campaign from already-resolved fields (content reference,
	 * recipients, title). Callers are responsible for having authorized the send
	 * and for sourcing these fields from a trusted campaign record: the scheduler
	 * supplies them from its admin-authenticated query, and
	 * {@link #executeCampaignById} from the record it fetches with the caller's
	 * token. The supplied {@code authHeaders} are forwarded on every downstream
	 * Liferay call made while assembling the email.
	 */
	public String executeCampaign(
		HttpHeaders authHeaders, String campaignId, String title,
		String objectDefinitionId, String objectDefinitionTitle,
		String objectEntryId, String recipientUserGroupIds,
		String recipientUserIds, String recipientEmailAddresses) {

		if (_log.isDebugEnabled()) {
			_log.debug(
				"Executing campaign id={}, title=\"{}\", " +
					"objectDefinitionId={}, objectEntryId={}",
				campaignId, title, objectDefinitionId, objectEntryId);
		}

		if (_isBlank(recipientUserGroupIds) && _isBlank(recipientUserIds) &&
			_isBlank(recipientEmailAddresses)) {

			_log.warn(
				"Campaign {} has no recipients configured", campaignId);

			return "No recipientUserGroupIds, recipientUserIds, or " +
				"recipientEmailAddresses provided";
		}

		String subject = "[Placeholder] Subject";
		String body = "[Placeholder] Body";

		List<Recipient> recipients = new ArrayList<>();
		Map<String, Recipient> recipientsByUserId = new HashMap<>();
		Set<String> addedUserIds = new HashSet<>();
		Set<String> addedEmailAddresses = new HashSet<>();

		try {
			RestTemplate restTemplate = _restTemplate;

			HttpHeaders headers = new HttpHeaders();

			headers.putAll(authHeaders);
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<String> entity = new HttpEntity<>(headers);

			// Fetch object entry content via object definition. Both ids are
			// interpolated into the headless API URL paths below, so accept only
			// well-formed numeric ids -- keeping the outbound fetch URLs clean
			// (no path/query injection or SSRF), mirroring the campaignId check
			// in executeCampaignById. The exact (untrimmed) value is what gets
			// interpolated, so it is the value validated here.

			if (!_isBlank(objectDefinitionId) && !_isBlank(objectEntryId) &&
				(!_isNumericId(objectDefinitionId) ||
				 !_isNumericId(objectEntryId))) {

				_log.warn(
					"Skipping content fetch for campaign id={}: " +
						"objectDefinitionId or objectEntryId is not numeric " +
						"(objectDefinitionId={}, objectEntryId={})",
					campaignId, objectDefinitionId, objectEntryId);
			}
			else if (!_isBlank(objectDefinitionId) &&
				!_isBlank(objectEntryId)) {

				try {
					String defUrl =
						_liferayHeadlessApiBaseUrl +
							"/object-admin/v1.0/object-definitions/" +
							objectDefinitionId;

					if (_log.isDebugEnabled()) {
						_log.debug(
							"Fetching Object Definition from: {}", defUrl);
					}

					ResponseEntity<String> defResponse =
						restTemplate.exchange(
							defUrl, HttpMethod.GET, entity, String.class);

					ObjectMapper mapper = new ObjectMapper();

					JsonNode defNode = mapper.readTree(
						defResponse.getBody());

					String restContextPath = defNode.path(
						"restContextPath").asText("");

					if (_log.isDebugEnabled()) {
						_log.debug(
							"Extracted restContextPath: {}",
							restContextPath);
					}

					// The rich-text body field is named differently per
					// content type (e.g. "content" for Basic Web Content and
					// Blog, "body" for News Article), so resolve it from the
					// object definition's RichText field rather than hardcoding.

					String bodyFieldName = "content";

					boolean bodyFieldResolved = false;

					// Names of all RichText fields. Those hold authored HTML, so
					// {{objectEntry.<field>}} merges of them are inlined raw (like the
					// body); every other (scalar) field is HTML-escaped below.

					Set<String> richTextFieldNames = new HashSet<>();

					JsonNode objectFieldsNode = defNode.path("objectFields");

					if (objectFieldsNode.isArray()) {
						for (JsonNode fieldNode : objectFieldsNode) {
							if (!"RichText".equals(
									fieldNode.path("businessType").asText())) {

								continue;
							}

							String richTextFieldName = fieldNode.path(
								"name").asText("content");

							richTextFieldNames.add(richTextFieldName);

							// The first RichText field is the email body.

							if (!bodyFieldResolved) {
								bodyFieldName = richTextFieldName;
								bodyFieldResolved = true;
							}
						}
					}

					if (_log.isDebugEnabled()) {
						_log.debug(
							"Resolved body field name: {}", bodyFieldName);
					}

					if (!restContextPath.isEmpty()) {
						String blastUrl;

						if (_liferayHeadlessApiBaseUrl.endsWith("/o") &&
							restContextPath.startsWith("/o/")) {

							blastUrl =
								_liferayHeadlessApiBaseUrl +
									restContextPath.substring(2) + "/" +
									objectEntryId;
						}
						else {
							blastUrl =
								_liferayHeadlessApiBaseUrl +
									restContextPath + "/" + objectEntryId;
						}

						if (_log.isDebugEnabled()) {
							_log.debug(
								"Fetching Object Entry from: {}", blastUrl);
						}

						ResponseEntity<String> blastResponse =
							restTemplate.exchange(
								blastUrl, HttpMethod.GET, entity,
								String.class);

						JsonNode blastNode = mapper.readTree(
							blastResponse.getBody());

						if (blastNode.has("title") &&
							!blastNode.path("title").isNull()) {

							subject = blastNode.path("title").asText();
						}

						if (blastNode.has(bodyFieldName) &&
							!blastNode.path(bodyFieldName).isNull()) {

							body = _fixRelativeImageUrls(
								blastNode.path(bodyFieldName).asText());

							Matcher matcher = Pattern.compile("\\{\\{objectEntry\\.([^}]+)\\}\\}").matcher(body);
							StringBuffer sb = new StringBuffer();

							while (matcher.find()) {
								String fieldName = matcher.group(1).trim();

								if (blastNode.has(fieldName) && !blastNode.path(fieldName).isNull()) {
									String fieldValue = blastNode.path(fieldName).asText();

									// RichText fields are authored HTML and inlined raw; every
									// other field is escaped so its value cannot inject markup.
									if (!richTextFieldNames.contains(fieldName)) {
										fieldValue = HtmlUtil.escape(fieldValue);
									}

									matcher.appendReplacement(sb, Matcher.quoteReplacement(fieldValue));
								} else {
									matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
								}
							}
							matcher.appendTail(sb);
							body = sb.toString();
						}

						if (title != null) {
							body = body.replace("{{campaign.title}}", HtmlUtil.escape(title));
						}
						
						if (objectDefinitionTitle != null) {
							body = body.replace("{{campaign.contentType}}", HtmlUtil.escape(objectDefinitionTitle));
						}

						// Fetch related objects and build
						// related items HTML

						String relatedItemsHtml =
							_buildRelatedItemsHtml(
								restTemplate, entity, mapper,
								objectDefinitionId, blastUrl,
								restContextPath);

						if (relatedItemsHtml != null &&
							!relatedItemsHtml.isEmpty()) {

							if (body.contains(
								"{{relatedItems}}")) {

								body = body.replace(
									"{{relatedItems}}",
									relatedItemsHtml);
							}
							else {
								body = body + relatedItemsHtml;
							}
						}
						else if (body.contains(
							"{{relatedItems}}")) {

							body = body.replace(
								"{{relatedItems}}", "");
						}

						// Construct portal URL

						String displayPagePath =
							_displayPageUrlService.resolveUrl(
								blastNode, headers, null);

						String portalUrl =
							(displayPagePath != null) ?
								_friendlyUrlBaseUrl + displayPagePath : null;

						if (portalUrl != null) {
							String separator =
								portalUrl.contains("?") ? "&" : "?";

							portalUrl =
								portalUrl + separator +
									"utm_source=email&utm_campaign=" +
									campaignId;

							if (_log.isDebugEnabled()) {
								_log.debug(
									"Constructed portal URL: {}",
									portalUrl);
							}

							if (body.contains("{{campaign.viewOnlineUrl}}")) {
								body = body.replace("{{campaign.viewOnlineUrl}}", HtmlUtil.escape(portalUrl));
							} else {
								body =
									"<a href=\"" + HtmlUtil.escape(portalUrl) +
										"\">View Online &#x2197;</a><br/>" +
										"<br/>" + body;
							}
						}
					}
					else {
						_log.warn(
							"restContextPath not found for " +
								"objectDefinitionId: {}",
							objectDefinitionId);
					}
				}
				catch (HttpClientErrorException e) {
					_log.error(
						"HTTP error fetching ObjectEntry: {} - {}",
						e.getStatusCode(), e.getResponseBodyAsString(), e);
				}
				catch (Exception e) {
					_log.warn(
						"Failed to fetch ObjectEntry for objectEntryId: {}",
						objectEntryId, e);
				}
			}

			// Fetch users from user group(s). recipientUserGroupIds may be a
			// comma-separated list (the UI joins the selected groups), so split
			// and process each id independently, mirroring recipientUserIds.

			if (!_isBlank(recipientUserGroupIds)) {
				String[] groupIdArray =
					recipientUserGroupIds.trim().split(",");

				for (String rawGroupId : groupIdArray) {
					String groupId = rawGroupId.trim();

					if (groupId.isEmpty()) {
						continue;
					}

					// The group id is interpolated into the headless API URL
					// path, so accept only numeric values -- keeping the
					// outbound URL clean (no path/query injection).

					if (!_isNumericId(groupId)) {
						_log.warn(
							"Ignoring non-numeric recipientUserGroupIds " +
								"value: {}",
							groupId);

						continue;
					}

					// First, fetch the UserGroup name

					String userGroupName = null;

					try {
						String groupUrl =
							_liferayHeadlessApiBaseUrl +
								"/headless-admin-user/v1.0/user-groups/" +
								groupId;

						if (_log.isDebugEnabled()) {
							_log.debug(
								"Fetching UserGroup name from: {}",
								groupUrl);
						}

						ResponseEntity<String> groupResponse =
							restTemplate.exchange(
								groupUrl, HttpMethod.GET, entity,
								String.class);

						ObjectMapper groupMapper = new ObjectMapper();

						JsonNode groupNode = groupMapper.readTree(
							groupResponse.getBody());

						userGroupName = groupNode.path(
							"name").asText(null);

						if (_log.isDebugEnabled()) {
							_log.debug(
								"UserGroup id={} name=\"{}\"",
								groupId,
								userGroupName);
						}
					}
					catch (Exception e) {
						_log.warn(
							"Failed to fetch UserGroup name for id={}",
							groupId, e);
					}

					// Fetch users from the user group

					int page = 1;
					boolean hasMorePages = true;

					while (hasMorePages) {
						String url =
							_liferayHeadlessApiBaseUrl +
								"/headless-admin-user/v1.0/user-groups/" +
								groupId +
								"/user-group-users?pageSize=100&page=" + page;

						ResponseEntity<String> response =
							restTemplate.exchange(
								url, HttpMethod.GET, entity, String.class);

						ObjectMapper objectMapper = new ObjectMapper();

						JsonNode rootNode = objectMapper.readTree(
							response.getBody());

						JsonNode itemsNode = rootNode.path("items");

						if (itemsNode.isArray() && itemsNode.size() > 0) {
							for (JsonNode item : itemsNode) {
								String emailAddress = item.path(
									"emailAddress").asText(null);
								String requestUserId = item.path(
									"id").asText(null);
								String userName = item.path(
									"name").asText(null);

								if (emailAddress != null &&
									!emailAddress.isEmpty() &&
									requestUserId != null &&
									!requestUserId.isEmpty()) {

									Recipient recipient;

									if (addedUserIds.contains(
										requestUserId)) {

										// User already added from
										// another source -- just tag
										// the group name

										recipient =
											recipientsByUserId.get(
												requestUserId);
									}
									else {
										recipient = new Recipient(
											emailAddress, requestUserId,
											userName);

										recipients.add(recipient);
										recipientsByUserId.put(
											requestUserId, recipient);
										addedUserIds.add(requestUserId);
										addedEmailAddresses.add(
											emailAddress.toLowerCase());
									}

									if (userGroupName != null &&
										recipient != null) {

										recipient.addUserGroupName(
											userGroupName);
									}
								}
							}
						}

						JsonNode lastPageNode = rootNode.path("lastPage");

						if (lastPageNode.isNumber()) {
							long lastPage = lastPageNode.asLong();

							if (page >= lastPage) {
								hasMorePages = false;
							}
							else {
								page++;
							}
						}
						else {
							hasMorePages = false;
						}
					}
				}
			}

			// Fetch individual users by recipientUserIds

			if (!_isBlank(recipientUserIds)) {
				String[] userIdArray = recipientUserIds.trim().split(",");

				StringBuilder filterBuilder = new StringBuilder();

				for (String uid : userIdArray) {
					uid = uid.trim();

					if (uid.isEmpty()) {
						continue;
					}

					// Liferay user-account ids are numeric. Reject anything
					// else so a crafted value cannot break out of the quoted
					// literal and inject OData filter logic (e.g. broadening
					// the result set to other users).

					if (!_isNumericId(uid)) {
						_log.warn(
							"Ignoring non-numeric recipientUserIds value: {}",
							uid);

						continue;
					}

					if (filterBuilder.length() > 0) {
						filterBuilder.append(" or ");
					}

					filterBuilder.append(
						"id eq '").append(uid).append("'");
				}

				if (filterBuilder.length() > 0) {
					String odataFilter =
						java.net.URLEncoder.encode(
							filterBuilder.toString(), "UTF-8");

					int userPage = 1;
					boolean hasMoreUserPages = true;

					if (_log.isDebugEnabled()) {
						_log.debug(
							"Fetching users with OData filter: {}",
							filterBuilder.toString());
					}

					while (hasMoreUserPages) {
						String userUrl =
							_liferayHeadlessApiBaseUrl +
								"/headless-admin-user/v1.0/" +
								"user-accounts?filter=" + odataFilter +
								"&pageSize=100&page=" + userPage;

						ResponseEntity<String> userResponse =
							restTemplate.exchange(
								URI.create(userUrl), HttpMethod.GET, entity,
								String.class);

						ObjectMapper userMapper = new ObjectMapper();

						JsonNode userRoot = userMapper.readTree(
							userResponse.getBody());

						JsonNode userItems = userRoot.path("items");

						if (userItems.isArray() &&
							userItems.size() > 0) {

							for (JsonNode userItem : userItems) {
								String emailAddress = userItem.path(
									"emailAddress").asText(null);
								String userId = userItem.path(
									"id").asText(null);
								String userName = userItem.path(
									"name").asText(null);

								if (emailAddress != null &&
									!emailAddress.isEmpty() &&
									userId != null &&
									!userId.isEmpty() &&
									!addedUserIds.contains(userId)) {

									Recipient recipient = new Recipient(
										emailAddress, userId, userName);

									recipients.add(recipient);
									recipientsByUserId.put(
										userId, recipient);
									addedUserIds.add(userId);
									addedEmailAddresses.add(
										emailAddress.toLowerCase());
								}
							}
						}

						JsonNode userLastPageNode = userRoot.path(
							"lastPage");

						if (userLastPageNode.isNumber()) {
							long lastPage = userLastPageNode.asLong();

							if (userPage >= lastPage) {
								hasMoreUserPages = false;
							}
							else {
								userPage++;
							}
						}
						else {
							hasMoreUserPages = false;
						}
					}
				}
			}

			// Look up UserGroups for individually-targeted
			// recipients who don't already have group assignments

			for (Recipient recipient : recipients) {
				if (recipient.getUserId() != null &&
					recipient.getUserGroupNames().isEmpty()) {

					try {
						String userGroupsUrl =
							_liferayHeadlessApiBaseUrl +
								"/headless-admin-user/v1.0/" +
								"user-accounts/" +
								recipient.getUserId() +
								"/user-groups";

						if (_log.isDebugEnabled()) {
							_log.debug(
								"Looking up UserGroups for " +
									"userId={}: {}",
								recipient.getUserId(),
								userGroupsUrl);
						}

						ResponseEntity<String> ugResponse =
							restTemplate.exchange(
								userGroupsUrl, HttpMethod.GET,
								entity, String.class);

						ObjectMapper ugMapper =
							new ObjectMapper();

						JsonNode ugRoot = ugMapper.readTree(
							ugResponse.getBody());

						JsonNode ugItems = ugRoot.path("items");

						if (ugItems.isArray()) {
							for (JsonNode ugItem : ugItems) {
								String groupName =
									ugItem.path(
										"name").asText(null);

								if (groupName != null) {
									recipient.addUserGroupName(
										groupName);
								}
							}
						}

						if (_log.isDebugEnabled()) {
							_log.debug(
								"userId={} belongs to " +
									"UserGroups: {}",
								recipient.getUserId(),
								recipient.getUserGroupNames());
						}
					}
					catch (Exception e) {
						_log.warn(
							"Failed to look up UserGroups " +
								"for userId={}",
							recipient.getUserId(), e);
					}
				}
			}

			// Add recipients from recipientEmailAddresses

			if (!_isBlank(recipientEmailAddresses)) {
				String[] emailArray =
					recipientEmailAddresses.trim().split(",");

				for (String rawEmail : emailArray) {
					String email = rawEmail.trim();

					if (email.isEmpty()) {
						continue;
					}

					if (!_EMAIL_PATTERN.matcher(email).matches()) {
						_log.error(
							"Invalid email address format, skipping: {}",
							email);

						continue;
					}

					if (addedEmailAddresses.contains(
						email.toLowerCase())) {

						if (_log.isDebugEnabled()) {
							_log.debug(
								"Skipping duplicate email address: {}",
								email);
						}

						continue;
					}

					recipients.add(new Recipient(email, null, null));
					addedEmailAddresses.add(email.toLowerCase());
				}
			}
		}
		catch (HttpClientErrorException e) {
			_log.error(
				"HTTP error fetching user group users: {} - {}",
				e.getStatusCode(), e.getResponseBodyAsString(), e);

			return "Error fetching user group users: " + e.getMessage();
		}
		catch (Exception e) {
			_log.error("Failed to fetch user group users", e);

			return "Failed to fetch user group users: " + e.getMessage();
		}

		if (recipients.isEmpty()) {
			return "No recipients found for the given recipient parameters";
		}

		if (_log.isInfoEnabled()) {
			_log.info(
				"Triggering email with subject: {} for campaign id={}, " +
					"objectEntryId={}",
				subject, campaignId, objectEntryId);
		}

		_emailSender.sendEmailToList(
			recipients, subject, body, String.valueOf(objectEntryId),
			String.valueOf(campaignId), String.valueOf(title),
			String.valueOf(objectDefinitionTitle));

		// Record that the campaign was sent so the send-once guard engages on
		// any subsequent send attempt (manual or scheduled).

		_stampLastSent(campaignId);

		return "Email sending triggered for " + recipients.size() +
			" recipients";
	}

	private String _buildRelatedItemsHtml(
		RestTemplate restTemplate, HttpEntity<String> entity,
		ObjectMapper mapper, String objectDefinitionId,
		String entryUrl, String restContextPath) {

		try {
			String relUrl =
				_liferayHeadlessApiBaseUrl +
					"/object-admin/v1.0/object-definitions/" +
					objectDefinitionId + "/object-relationships";

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Fetching object relationships from: {}", relUrl);
			}

			ResponseEntity<String> relResponse =
				restTemplate.exchange(
					relUrl, HttpMethod.GET, entity, String.class);

			JsonNode relRoot = mapper.readTree(
				relResponse.getBody());

			JsonNode relItems = relRoot.path("items");

			if (!relItems.isArray() || relItems.size() == 0) {
				if (_log.isDebugEnabled()) {
					_log.debug(
						"No relationships found for " +
							"objectDefinitionId: {}",
						objectDefinitionId);
				}

				return "";
			}

			// Collect relationship metadata

			List<String> relNames = new ArrayList<>();
			List<String> relLabels = new ArrayList<>();

			for (JsonNode rel : relItems) {
				if (rel.path("reverse").asBoolean(false)) {
					continue;
				}

				String name = rel.path("name").asText("");
				String label = rel.has("label") ?
					_extractLocalizedText(rel.path("label")) :
					name;

				if (!name.isEmpty()) {
					relNames.add(name);
					relLabels.add(label);
				}
			}

			if (relNames.isEmpty()) {
				return "";
			}

			// Re-fetch the entry with nestedFields

			String nestedFieldsParam = String.join(",", relNames);

			String separator = entryUrl.contains("?") ? "&" : "?";

			String nestedUrl =
				entryUrl + separator + "nestedFields=" +
					nestedFieldsParam + "&nestedFieldsDepth=1";

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Re-fetching entry with nestedFields: {}",
					nestedUrl);
			}

			ResponseEntity<String> nestedResponse =
				restTemplate.exchange(
					nestedUrl, HttpMethod.GET, entity,
					String.class);

			JsonNode nestedEntry = mapper.readTree(
				nestedResponse.getBody());

			// Build HTML for related items

			StringBuilder html = new StringBuilder();

			html.append(
				"<div style=\"margin-top:24px;" +
					"padding-top:16px;" +
					"border-top:1px solid #e0e0e0;\">");

			boolean hasAnyItems = false;

			for (int i = 0; i < relNames.size(); i++) {
				String relName = relNames.get(i);
				String relLabel = relLabels.get(i);

				JsonNode itemsArray = nestedEntry.path(relName);

				if (!itemsArray.isArray() ||
					itemsArray.size() == 0) {

					continue;
				}

				hasAnyItems = true;

				html.append(
					"<h4 style=\"margin:12px 0 8px 0;" +
						"font-size:14px;color:#555;\">");
				html.append(_escapeHtml(relLabel));
				html.append("</h4>");
				html.append("<ul style=\"margin:0;padding:0 0 0 20px;\">");

				for (JsonNode relatedItem : itemsArray) {
					String itemTitle = relatedItem.path(
						"title").asText("");

					if (itemTitle.isEmpty()) {
						itemTitle = relatedItem.path(
							"id").asText("Untitled");
					}

					// Build display page URL for this
					// related item

					String relatedPath =
						_displayPageUrlService.resolveUrl(
							relatedItem, entity.getHeaders(), null);

					String itemUrl =
						(relatedPath != null) ?
							_friendlyUrlBaseUrl + relatedPath : null;

					html.append(
						"<li style=\"margin:4px 0;\">");

					if (itemUrl != null) {
						html.append(
							"<a href=\"" +
								_escapeHtml(itemUrl) +
								"\" style=\"color:#0b5fff;" +
								"text-decoration:none;\">");
						html.append(_escapeHtml(itemTitle));
						html.append(" &#x2197;</a>");
					}
					else {
						html.append(_escapeHtml(itemTitle));
					}

					html.append("</li>");
				}

				html.append("</ul>");
			}

			html.append("</div>");

			if (!hasAnyItems) {
				return "";
			}

			return html.toString();
		}
		catch (Exception e) {
			_log.warn(
				"Failed to build related items HTML for " +
					"objectDefinitionId: {}",
				objectDefinitionId, e);

			return "";
		}
	}

	private String _escapeHtml(String text) {
		return HtmlUtil.escape(text);
	}

	private String _extractLocalizedText(JsonNode labelNode) {
		if (labelNode.has("en_US")) {
			return labelNode.path("en_US").asText("");
		}

		// Fall back to first available locale

		java.util.Iterator<String> fieldNames =
			labelNode.fieldNames();

		if (fieldNames.hasNext()) {
			return labelNode.path(fieldNames.next()).asText("");
		}

		return "";
	}

	private String _fixRelativeImageUrls(String html) {
		if (html == null || html.isEmpty()) {
			return html;
		}

		Matcher matcher = _IMG_SRC_PATTERN.matcher(html);

		StringBuffer sb = new StringBuffer();

		while (matcher.find()) {
			String prefix = matcher.group(1);
			String src = matcher.group(2);

			if (src.startsWith("/") && !src.startsWith("//")) {
				matcher.appendReplacement(
					sb, Matcher.quoteReplacement(
						prefix + _liferayBaseUrl + src));
			}
			else {
				matcher.appendReplacement(
					sb, Matcher.quoteReplacement(matcher.group(0)));
			}
		}

		matcher.appendTail(sb);

		return sb.toString();
	}

	/**
	 * Mirrors the browser's "Send Now is offered only for approved campaigns"
	 * rule on the server. The Objects REST representation exposes workflow status
	 * as a {@code status} object ({@code code}/{@code label}); Approved is code 0.
	 * Tolerates a bare numeric/string status for representations without the
	 * nested object.
	 *
	 * <p>When the Campaign object has no workflow linked, Liferay creates every
	 * entry already in the Approved state, so this is a no-op; it matters only
	 * when an administrator has optionally attached an approval workflow.</p>
	 */
	public boolean isApproved(JsonNode campaign) {
		JsonNode statusNode = campaign.path("status");

		if (statusNode.isObject()) {
			if (statusNode.path("code").asInt(-1) == 0) {
				return true;
			}

			return "approved".equalsIgnoreCase(
				statusNode.path("label").asText(""));
		}

		if (statusNode.isNumber()) {
			return statusNode.asInt(-1) == 0;
		}

		String status = statusNode.asText("");

		return "0".equals(status) || "approved".equalsIgnoreCase(status);
	}

	/**
	 * Whether the campaign has already been sent, i.e. its {@code lastSentDate}
	 * is populated. Used by both the manual send path and the scheduler to honor
	 * the send-once guard until an administrator clears the field.
	 */
	public boolean isAlreadySent(JsonNode campaign) {
		String lastSentDate = campaign.path("lastSentDate").asText("");

		return !lastSentDate.isBlank() && !"null".equalsIgnoreCase(lastSentDate);
	}

	private boolean _isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private boolean _isNumericId(String value) {
		return value != null && _NUMERIC_ID_PATTERN.matcher(value).matches();
	}

	/**
	 * Stamps {@code lastSentDate} on the campaign so the send-once guard engages
	 * on any later send. The write uses the configured admin credentials rather
	 * than the caller's token: it is the service's own bookkeeping, and the
	 * read-only OAuth scope granted to callers cannot modify campaigns. PATCH is
	 * issued with {@link HttpClient} because the shared {@code RestTemplate} runs
	 * on {@code SimpleClientHttpRequestFactory}, which cannot send PATCH.
	 *
	 * <p>Best effort: a failure here (or unconfigured credentials) is logged but
	 * does not fail the send. When credentials are absent the guard simply never
	 * engages, so re-sends are not blocked.</p>
	 */
	private void _stampLastSent(String campaignId) {
		if (_isBlank(_liferayEmail) || _isBlank(_liferayPassword)) {
			_log.warn(
				"Not stamping lastSentDate for campaign id={}: liferay.email / " +
					"liferay.password not configured, so the send-once guard " +
					"will not engage",
				campaignId);

			return;
		}

		if (!_isNumericId(campaignId)) {
			_log.warn(
				"Not stamping lastSentDate: campaignId is not numeric ({})",
				campaignId);

			return;
		}

		try {
			String nowIso = OffsetDateTime.now(
				ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(
					DateTimeFormatter.ISO_INSTANT);

			String encodedCredentials = Base64.getEncoder().encodeToString(
				(_liferayEmail + ":" + _liferayPassword).getBytes(
					StandardCharsets.UTF_8));

			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(
					_liferayHeadlessApiBaseUrl + "/c/campaigns/" + campaignId))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Basic " + encodedCredentials)
				.header("Content-Type", "application/json")
				.method(
					"PATCH",
					HttpRequest.BodyPublishers.ofString(
						"{\"lastSentDate\":\"" + nowIso + "\"}"))
				.build();

			HttpResponse<String> response = client.send(
				request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 300) {
				_log.warn(
					"Failed to stamp lastSentDate for campaign id={}: HTTP {}",
					campaignId, response.statusCode());
			}
			else if (_log.isDebugEnabled()) {
				_log.debug(
					"Stamped lastSentDate={} on campaign id={}", nowIso,
					campaignId);
			}
		}
		catch (Exception e) {
			_log.warn(
				"Failed to stamp lastSentDate for campaign id={}", campaignId,
				e);
		}
	}

	private static final Pattern _EMAIL_PATTERN = Pattern.compile(
		"^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

	private static final Pattern _NUMERIC_ID_PATTERN = Pattern.compile(
		"\\d+");

	private static final Pattern _IMG_SRC_PATTERN = Pattern.compile(
		"(<img\\b[^>]*?\\bsrc=\")([^\"]*)",
		Pattern.CASE_INSENSITIVE);

	private static final Logger _log = LoggerFactory.getLogger(
		CampaignExecutionService.class);

	@Autowired
	private DisplayPageUrlService _displayPageUrlService;

	@Autowired
	private EmailSender _emailSender;

	@Autowired
	private RestTemplate _restTemplate;

	@Value("${email.friendly.url.base.url:http://localhost:8080}")
	private String _friendlyUrlBaseUrl;

	@Value("${liferay.email:}")
	private String _liferayEmail;

	@Value("${liferay.password:}")
	private String _liferayPassword;

	@Value("${liferay.base.url:http://localhost:8080}")
	private String _liferayBaseUrl;

	@Value("${liferay.headless.api.base.url:http://localhost:8080/o}")
	private String _liferayHeadlessApiBaseUrl;

}
