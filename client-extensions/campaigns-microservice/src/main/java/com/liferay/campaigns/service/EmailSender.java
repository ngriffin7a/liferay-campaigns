// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import com.liferay.campaigns.dto.Recipient;
import com.liferay.campaigns.util.HtmlUtil;
import com.liferay.campaigns.util.LogUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailSender {

    public void sendEmailToList(List<Recipient> recipients, String subject, String body, String objectEntryId,
            String campaignId, String campaignTitle, String contentType) {
        for (Recipient recipient : recipients) {
            String emailAddress = recipient.getEmailAddress();
            String userId = recipient.getUserId();
            String name = recipient.getName();

            try {
                MimeMessage mimeMessage = _javaMailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");

                if (name != null && !name.isEmpty()) {
                    helper.setTo(new InternetAddress(emailAddress, name));
                } else {
                    helper.setTo(emailAddress);
                }
                helper.setSubject(subject);

                // Issued-at timestamp bounds how long this pixel stays valid
                // (see TrackingTokenService.isFresh).
                String issuedAt = _trackingTokenService.newIssuedAt();

                // The tracking-pixel parameters carry PII (the recipient email
                // address) and campaign/content metadata. Pack them into a single
                // encrypted, authenticated token so none of it appears in
                // cleartext in the URL (and therefore in access logs, proxies, or
                // Referer headers); the controller decrypts it to recover the
                // values. The issued-at lives inside the token so it cannot be
                // altered to extend the URL's lifetime.
                String trackingUrl;

                if (_trackingTokenService.isEnabled()) {
                    Map<String, String> fields = new HashMap<>();

                    fields.put("objectEntryId", objectEntryId);
                    fields.put("userId", userId != null ? userId : "");
                    fields.put("campaignId", campaignId);
                    fields.put("campaignTitle", campaignTitle);
                    fields.put("contentType", contentType);
                    fields.put("contentId", objectEntryId);
                    fields.put("contentTitle", subject);
                    fields.put("emailAddress", emailAddress);
                    fields.put("iat", issuedAt);

                    trackingUrl = _trackingPixelUrl(
                        "t=" + java.net.URLEncoder.encode(
                            _trackingTokenService.encrypt(fields), "UTF-8"));
                }
                else {

                    // No secret configured: there is no key to encrypt with, so
                    // fall back to cleartext parameters. This path runs only when
                    // tracking.pixel.allow-unsigned=true (local development); see
                    // TrackingTokenService for why encrypted tokens are the
                    // production default.
                    StringBuilder params = new StringBuilder();

                    params.append("objectEntryId=").append(
                        java.net.URLEncoder.encode(objectEntryId, "UTF-8"));
                    params.append("&userId=").append(userId != null ? userId : "");
                    params.append("&iat=").append(issuedAt);
                    params.append("&emailAddress=").append(
                        java.net.URLEncoder.encode(emailAddress, "UTF-8"));

                    if (campaignId != null) {
                        params.append("&campaignId=").append(
                            java.net.URLEncoder.encode(campaignId, "UTF-8"));
                    }
                    if (campaignTitle != null) {
                        params.append("&campaignTitle=").append(
                            java.net.URLEncoder.encode(campaignTitle, "UTF-8"));
                    }
                    if (contentType != null) {
                        params.append("&contentType=").append(
                            java.net.URLEncoder.encode(contentType, "UTF-8"));
                    }
                    params.append("&contentId=").append(
                        java.net.URLEncoder.encode(objectEntryId, "UTF-8"));
                    if (subject != null) {
                        params.append("&contentTitle=").append(
                            java.net.URLEncoder.encode(subject, "UTF-8"));
                    }

                    trackingUrl = _trackingPixelUrl(params.toString());
                }

                String htmlBody = body + "<br/><img src=\"" + trackingUrl
                        + "\" width=\"1\" height=\"1\" border=\"0\" />";

                // Process UserGroup conditional blocks

                htmlBody = _processUserGroupBlocks(
                    htmlBody, recipient.getUserGroupNames());

                // Recipient values are plain text, so HTML-escape them before
                // merging into the markup; they then render safely as literal
                // text (e.g. a name like "Tom & Jerry") and never as markup.
                if (name != null) {
                    htmlBody = htmlBody.replace("{{recipient.name}}", HtmlUtil.escape(name));
                } else {
                    htmlBody = htmlBody.replace("{{recipient.name}}", "Friend");
                }

                if (emailAddress != null) {
                    htmlBody = htmlBody.replace("{{recipient.email}}", HtmlUtil.escape(emailAddress));
                }

                if (userId != null) {
                    htmlBody = htmlBody.replace("{{recipient.userId}}", HtmlUtil.escape(userId));
                }

                helper.setText(htmlBody, true);

                _javaMailSender.send(mimeMessage);

                if (_log.isInfoEnabled()) {
                    _log.info("Email sent to " + LogUtil.maskEmail(emailAddress) + " for campaign id=" + campaignId);
                }

                // The tracking URL carries the email address and the HMAC
                // signature, so keep it at DEBUG only.
                if (_log.isDebugEnabled()) {
                    _log.debug("Tracking pixel for " + LogUtil.maskEmail(emailAddress) + ": " + trackingUrl);
                }
            } catch (Exception e) {
                if (_log.isErrorEnabled()) {
                    _log.error("Failed to send email to " + LogUtil.maskEmail(emailAddress), e);
                }
            }
        }
    }

    /**
     * Process UserGroup conditional blocks in the HTML template.
     *
     * Syntax:
     *   {{#Field Engineers}}...content...{{/Field Engineers}}
     *   {{#Executives}}...content...{{/Executives}}
     *   {{#default}}...fallback content...{{/default}}
     *
     * Rules:
     * - All blocks whose group name matches the recipient's
     *   UserGroup memberships are kept (unwrapped)
     * - Non-matching blocks are removed
     * - {{#default}} content is kept only if no other block matched
     * - Group name matching is case-insensitive
     */
    private String _processUserGroupBlocks(
        String html, Set<String> userGroupNames) {

        if (html == null || !html.contains("{{#")) {
            return html;
        }

        // Find all conditional blocks:
        // {{#GroupName}}...content...{{/GroupName}}

        Matcher matcher = _USER_GROUP_BLOCK_PATTERN.matcher(html);

        List<int[]> matchPositions = new ArrayList<>();
        List<String> matchGroupNames = new ArrayList<>();
        List<String> matchContents = new ArrayList<>();

        while (matcher.find()) {
            matchPositions.add(
                new int[] {matcher.start(), matcher.end()});
            matchGroupNames.add(matcher.group(1).trim());
            matchContents.add(matcher.group(2));
        }

        if (matchPositions.isEmpty()) {
            return html;
        }

        boolean anyNonDefaultMatched = false;
        String defaultContent = null;
        int defaultStart = -1;
        int defaultEnd = -1;

        // Build the result by processing matches in reverse order
        // to preserve string positions

        StringBuilder result = new StringBuilder(html);

        for (int i = matchPositions.size() - 1; i >= 0; i--) {
            int start = matchPositions.get(i)[0];
            int end = matchPositions.get(i)[1];
            String groupName = matchGroupNames.get(i);
            String content = matchContents.get(i);

            if (groupName.equalsIgnoreCase("default")) {
                defaultContent = content;
                defaultStart = start;
                defaultEnd = end;

                // Don't process default yet; handle it after
                // we know if any other blocks matched

                continue;
            }

            boolean matches = false;

            for (String recipientGroup : userGroupNames) {
                if (recipientGroup.equalsIgnoreCase(groupName)) {
                    matches = true;

                    break;
                }
            }

            if (matches) {
                anyNonDefaultMatched = true;

                // Keep the content, remove the wrapper tags

                result.replace(start, end, content);
            }
            else {
                // Remove the entire block

                result.replace(start, end, "");
            }
        }

        // Now handle the default block

        String resultStr = result.toString();

        if (defaultContent != null) {
            // Re-find default block in the modified string since
            // positions may have shifted

            Matcher defaultMatcher =
                _DEFAULT_BLOCK_PATTERN.matcher(resultStr);

            if (defaultMatcher.find()) {
                if (anyNonDefaultMatched) {
                    // Remove default block

                    resultStr = defaultMatcher.replaceFirst("");
                }
                else {
                    // Keep default content, remove wrapper tags

                    resultStr = defaultMatcher.replaceFirst(
                        Matcher.quoteReplacement(
                            defaultMatcher.group(1)));
                }
            }
        }

        return resultStr;
    }

    // Joins the configured base URL to the tracking-pixel path and query,
    // tolerating a base URL with or without a trailing slash.
    private String _trackingPixelUrl(String query) {
        String base = _trackingPixelBaseUrl;

        if (!base.endsWith("/")) {
            base = base + "/";
        }

        return base + "tracking-pixel?" + query;
    }

    // Matches {{#GroupName}}...content...{{/GroupName}} blocks
    // Group 1 = group name, Group 2 = inner content
    // Uses DOTALL so . matches newlines

    private static final Pattern _USER_GROUP_BLOCK_PATTERN =
        Pattern.compile(
            "\\{\\{#(.+?)\\}\\}([\\s\\S]*?)\\{\\{/\\1\\}\\}",
            Pattern.CASE_INSENSITIVE);

    // Matches {{#default}}...content...{{/default}} specifically

    private static final Pattern _DEFAULT_BLOCK_PATTERN =
        Pattern.compile(
            "\\{\\{#default\\}\\}([\\s\\S]*?)\\{\\{/default\\}\\}",
            Pattern.CASE_INSENSITIVE);

    @Value("${tracking.pixel.base.url:http://localhost:58081}")
    private String _trackingPixelBaseUrl;

    private static final Log _log = LogFactory.getLog(EmailSender.class);

    @Autowired
    private JavaMailSender _javaMailSender;

    @Autowired
    private TrackingTokenService _trackingTokenService;

}
