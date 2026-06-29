// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.service;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class EmailTracker {

    private static final Log _log = LogFactory.getLog(EmailTracker.class);

    @Autowired
    private RestTemplate _restTemplate;

    @Value("${analytics.endpoint.url}")
    private String analyticsEndpointUrl;

    @Value("${analytics.project.id}")
    private String analyticsProjectId;

    @Value("${analytics.datasource.id}")
    private String analyticsDatasourceId;

    @Value("${analytics.channel.id}")
    private String analyticsChannelId;

    public void trackEmailOpen(String objectEntryId, String userId, Map<String, String> requestInfo,
            String campaignId, String campaignTitle, String contentType, String contentId, String contentTitle, String emailAddress) {
        try {
            if ("REPLACE_WITH_PROJECT_ID".equals(analyticsProjectId)) {
                _log.warn("Analytics tracking skipped: analytics.project.id is not configured");
                return;
            }

            RestTemplate restTemplate = _restTemplate;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("OSB-Asah-Project-ID", analyticsProjectId);

            // Removed explicit forwarding of User-Agent, Accept-Language, and X-Forwarded-For
            // Some tracking backends crash with HTTP 500 when parsing malformed X-Forwarded-For or IPv6 local IPs
            // To isolate the issue, sending only the required OSB-Asah-Project-ID header for now.
            
            String userAgent = requestInfo.get(HttpHeaders.USER_AGENT);

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            // Use a fixed formatter that always produces exactly 3 millisecond digits
            // matching the working payload format: "2026-03-13T14:04:53.072Z"
            DateTimeFormatter fixedMillisFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

            String eventDate = fixedMillisFormatter.format(now.atOffset(ZoneOffset.UTC));

            // Approximate local date using -04:00 (EDT)
            ZoneOffset offset = ZoneOffset.of("-04:00");
            OffsetDateTime localNow = now.atOffset(offset);
            String eventLocalDateFormatted = fixedMillisFormatter.format(localNow);

            Map<String, Object> context = new TreeMap<>();
            context.put("canonicalUrl", "");
            context.put("contentLanguageId", "en-US");
            context.put("description", "");
            context.put("devicePixelRatio", "");
            context.put("experienceId", "DEFAULT");
            context.put("experimentId", "");
            context.put("groupId", "20126");
            context.put("keywords", "");
            context.put("languageId", "en-US");
            // layoutExternalReferenceCode MUST be a non-empty UUID — empty string causes 500
            context.put("layoutExternalReferenceCode", UUID.randomUUID().toString());
            context.put("referrer", "");
            context.put("screenHeight", "");
            context.put("screenWidth", "");
            context.put("timezoneOffset", "-04:00");
            context.put("title", contentTitle != null ? contentTitle : "");
            context.put("url", "");
            context.put("userAgent", userAgent != null ? userAgent : "");
            context.put("variantId", "");

            String contextHash = _calculateContextHash(context);

            // Construct expected Analytics JSON Payload
            Map<String, Object> event = Map.of(
                    "applicationId", "CustomEvent",
                    "contextHash", contextHash,
                    "eventDate", eventDate,
                    "eventLocalDate", eventLocalDateFormatted,
                    "eventId", "emailOpened",
                    "properties", Map.of(
                            "campaignId", campaignId != null ? campaignId : "",
                            "campaignTitle", campaignTitle != null ? campaignTitle : "",
                            "contentType", contentType != null ? contentType : "",
                            "contentId", contentId != null ? contentId : "",
                            "contentTitle", contentTitle != null ? contentTitle : "",
                            "emailAddress", emailAddress != null ? emailAddress : ""));

            // emailAddressHashed cannot be empty or the Analytics backend crashes with 500.
            // If we have the user's emailAddress, we hash it. Otherwise, we hash their userId as a fallback.
            String emailAddressHashed = "";
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String inputToHash = (emailAddress != null && !emailAddress.isEmpty()) ? emailAddress : userId;
                byte[] hashBytes = md.digest(inputToHash.getBytes("UTF-8"));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hashBytes) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                emailAddressHashed = hexString.toString();
            } catch (Exception e) {
                // Fallback valid 64-char string if hashing fails
                emailAddressHashed = "0000000000000000000000000000000000000000000000000000000000000000";
            }

            // The analytics backend expects userId in UUID format, not numeric Liferay IDs.
            // Generate a deterministic UUID from the numeric userId so the same user
            // always maps to the same analytics userId.
            String analyticsUserId;
            try {
                UUID.fromString(userId);  // check if already a UUID
                analyticsUserId = userId;
            } catch (IllegalArgumentException e) {
                // Numeric ID — convert to deterministic UUID
                analyticsUserId = UUID.nameUUIDFromBytes(userId.getBytes("UTF-8")).toString();
            }

            Map<String, Object> payload = Map.of(
                    "channelId", analyticsChannelId,
                    "context", context,
                    "dataSourceId", analyticsDatasourceId,
                    "emailAddressHashed", emailAddressHashed,
                    "events", Collections.singletonList(event),
                    "id", UUID.randomUUID().toString(),
                    "userId", analyticsUserId);

            ObjectMapper objectMapper = new ObjectMapper();
            String jsonPayload = objectMapper.writeValueAsString(payload);

            // Register identity with AC so it can resolve this userId to a known individual
            _registerIdentity(restTemplate, headers, analyticsUserId, emailAddressHashed);

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

            // The payload contains PII (email address, hashed email, user id);
            // keep it at DEBUG so it does not land in logs in normal operation.
            if (_log.isDebugEnabled()) {
                _log.debug("Sending Analytics payload to " + analyticsEndpointUrl + ": " + jsonPayload);
            }

            org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                    analyticsEndpointUrl, entity, String.class);

            if (_log.isDebugEnabled()) {
                _log.debug(String.format(
                        "Analytics event sent successfully:\nURL: %s\nMethod: POST\nStatus: %s\nResponse: %s\nEventDate: %s",
                        analyticsEndpointUrl, responseEntity.getStatusCode(), responseEntity.getBody(), eventDate));
            }

        } catch (Exception e) {
            _log.error("Failed to send analytics event", e);
        }
    }

    private void _registerIdentity(RestTemplate restTemplate, HttpHeaders headers, String userId, String emailAddressHashed) {
        try {
            // Compute the identity request id as SHA-256 of userId + emailAddressHashed + dataSourceId
            // This mirrors the pattern used by the Liferay Analytics JS client
            String idInput = userId + emailAddressHashed + analyticsDatasourceId;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(idInput.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String identityId = hexString.toString();

            Map<String, Object> identityPayload = Map.of(
                    "channelId", analyticsChannelId,
                    "dataSourceId", analyticsDatasourceId,
                    "emailAddressHashed", emailAddressHashed,
                    "id", identityId,
                    "userId", userId);

            ObjectMapper objectMapper = new ObjectMapper();
            String jsonPayload = objectMapper.writeValueAsString(identityPayload);

            String identityUrl = analyticsEndpointUrl.replaceAll("/$", "") + "/identity";

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

            // The identity payload contains the hashed email and user id; keep
            // it (and the per-open response) at DEBUG.
            if (_log.isDebugEnabled()) {
                _log.debug("Sending Analytics identity to " + identityUrl + ": " + jsonPayload);
            }

            org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                    identityUrl, entity, String.class);

            if (_log.isDebugEnabled()) {
                _log.debug("Analytics identity response: " + responseEntity.getStatusCode());
            }
        } catch (Exception e) {
            _log.warn("Failed to register identity with Analytics Cloud (non-fatal)", e);
        }
    }

    private String _calculateContextHash(Map<String, Object> context) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(new TreeMap<>(context));

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes("UTF-8"));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            _log.error("Failed to calculate contextHash", e);
            return "";
        }
    }

}
