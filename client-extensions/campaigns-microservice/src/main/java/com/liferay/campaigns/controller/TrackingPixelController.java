// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.controller;

import com.liferay.campaigns.config.TrackingExecutorConfig;
import com.liferay.campaigns.service.EmailTracker;
import com.liferay.campaigns.service.TrackingTokenService;
import com.liferay.campaigns.util.LogUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrackingPixelController {

    @GetMapping(value = "/tracking-pixel", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> trackingPixel(
            @RequestParam(value = "t", required = false) String token,
            @RequestParam(value = "objectEntryId", required = false) String objectEntryId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "campaignId", required = false) String campaignId,
            @RequestParam(value = "campaignTitle", required = false) String campaignTitle,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "contentId", required = false) String contentId,
            @RequestParam(value = "contentTitle", required = false) String contentTitle,
            @RequestParam(value = "emailAddress", required = false) String emailAddress,
            @RequestParam(value = "iat", required = false) String iat,
            HttpServletRequest request) {

        // Recover the tracking fields. In normal operation they arrive as a
        // single encrypted token "t", which keeps the email address and metadata
        // out of the URL and lets decrypt() confirm the event is authentic -- a
        // fabricated or altered token yields null and is dropped. The
        // cleartext-parameter path is honored only when no secret is configured
        // and tracking.pixel.allow-unsigned is set (local development).
        Map<String, String> fields;

        if (_trackingTokenService.isEnabled()) {
            fields = _trackingTokenService.decrypt(token);

            if (fields == null) {
                if (_log.isWarnEnabled()) {
                    _log.warn(
                        "Rejected tracking-pixel request with missing or " +
                            "invalid token");
                }

                return _pixelResponse();
            }
        }
        else if (_trackingTokenService.isUnsignedAllowed()) {
            fields = new HashMap<>();

            fields.put("objectEntryId", objectEntryId);
            fields.put("userId", userId != null ? userId : "");
            fields.put("campaignId", campaignId);
            fields.put("campaignTitle", campaignTitle);
            fields.put("contentType", contentType);
            fields.put("contentId", contentId);
            fields.put("contentTitle", contentTitle);
            fields.put("emailAddress", emailAddress);
            fields.put("iat", iat != null ? iat : "");
        }
        else {

            // Fail closed: no secret configured and unsigned events not allowed.

            if (_log.isWarnEnabled()) {
                _log.warn(
                    "Dropping tracking-pixel event: tracking.pixel.secret is " +
                        "not configured (fail closed)");
            }

            return _pixelResponse();
        }

        String resolvedObjectEntryId = fields.get("objectEntryId");
        String resolvedUserId = fields.get("userId");
        String resolvedCampaignId = fields.get("campaignId");
        String resolvedCampaignTitle = fields.get("campaignTitle");
        String resolvedContentType = fields.get("contentType");
        String resolvedContentId = fields.get("contentId");
        String resolvedContentTitle = fields.get("contentTitle");
        String resolvedEmailAddress = fields.get("emailAddress");
        String resolvedIat = fields.get("iat");

        // Bound replay by dropping pixels older than the configured max age. The
        // iat is authentic (it came from inside the encrypted token), so it
        // cannot be altered to extend the URL's lifetime. The transparent GIF is
        // still returned so the email renders.
        if (!_trackingTokenService.isFresh(resolvedIat)) {
            if (_log.isWarnEnabled()) {
                _log.warn(
                    "Dropping expired tracking-pixel event for objectEntryId=" +
                        resolvedObjectEntryId + " (iat=" + resolvedIat + ")");
            }

            return _pixelResponse();
        }

        if (_log.isInfoEnabled()) {
            _log.info("Tracking pixel hit: objectEntryId=" + resolvedObjectEntryId + ", userId=" + resolvedUserId + ", emailAddress=" + LogUtil.maskEmail(resolvedEmailAddress));
        }

        // Extract necessary headers and info before backgrounding
        java.util.Map<String, String> requestInfo = new java.util.HashMap<>();
        requestInfo.put(org.springframework.http.HttpHeaders.USER_AGENT, request.getHeader(org.springframework.http.HttpHeaders.USER_AGENT));
        requestInfo.put(org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE, request.getHeader(org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE));
        requestInfo.put("X-Forwarded-For", request.getHeader("X-Forwarded-For"));
        requestInfo.put("RemoteAddr", request.getRemoteAddr());
        requestInfo.put("RequestURL", request.getRequestURL().toString());

        // Fire off the analytics request on a bounded pool so the public,
        // unauthenticated endpoint stays responsive under load: the pool + queue
        // cap how much work a burst of pixel hits can pin down at once. When they
        // saturate, the task is rejected and the event dropped rather than
        // allowed to exhaust threads/heap, so the service keeps serving.
        try {
            _trackingExecutor.execute(
                () -> _emailTracker.trackEmailOpen(
                    resolvedObjectEntryId, resolvedUserId, requestInfo,
                    resolvedCampaignId, resolvedCampaignTitle,
                    resolvedContentType, resolvedContentId,
                    resolvedContentTitle, resolvedEmailAddress));
        }
        catch (TaskRejectedException taskRejectedException) {
            if (_log.isWarnEnabled()) {
                _log.warn(
                    "Tracking executor saturated; dropping email-open event " +
                        "for objectEntryId=" + resolvedObjectEntryId,
                    taskRejectedException);
            }
        }

        return _pixelResponse();
    }

    private ResponseEntity<byte[]> _pixelResponse() {

        // 1x1 transparent GIF
        byte[] pixel = {
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00,
                0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00, 0x00, 0x00, 0x21, (byte) 0xf9,
                0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
                0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
        };

        // Referrer-Policy: no-referrer stops the (token-bearing) pixel URL from
        // being forwarded in a Referer header to any resource the email client
        // might load afterwards.
        return ResponseEntity.ok()
            .header("Referrer-Policy", "no-referrer")
            .contentType(MediaType.IMAGE_GIF)
            .body(pixel);
    }

    private static final Log _log = LogFactory.getLog(TrackingPixelController.class);

    @Autowired
    private EmailTracker _emailTracker;

    @Autowired
    private TrackingTokenService _trackingTokenService;

    @Autowired
    @Qualifier(TrackingExecutorConfig.TRACKING_EXECUTOR)
    private ThreadPoolTaskExecutor _trackingExecutor;

}
