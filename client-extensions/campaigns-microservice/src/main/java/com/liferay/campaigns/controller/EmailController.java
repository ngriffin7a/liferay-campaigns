// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.controller;

import com.liferay.campaigns.service.CampaignExecutionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

@RestController
public class EmailController {

    // Send a campaign by id only. The recipients and content are deliberately
    // NOT accepted from the request body: the service re-fetches the campaign
    // record from Liferay using the caller's JWT, so (a) Liferay enforces that
    // the caller may access the campaign, and (b) a caller cannot substitute
    // arbitrary recipients or content. The "approved" status is also re-checked
    // server-side rather than relying on the browser to hide "Send Now".
    @PostMapping("/send-email/{campaignId}")
    public String sendEmail(
        @AuthenticationPrincipal Jwt jwt, @PathVariable String campaignId) {

        if (jwt == null) {
            _log.warn("No JWT provided; rejecting send-email request.");
            return "Unauthorized: No JWT provided.";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.getTokenValue());

        return _campaignExecutionService.executeCampaignById(
            headers, campaignId);
    }

    private static final Log _log = LogFactory.getLog(EmailController.class);

    @Autowired
    private CampaignExecutionService _campaignExecutionService;

}
