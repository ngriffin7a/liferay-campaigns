// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.scheduler;

import com.liferay.campaigns.service.CampaignExecutionService;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class CampaignScheduler {

	@Scheduled(fixedRate = 60000)
	public void checkForScheduledCampaigns() {
		if (_liferayEmail.isEmpty() || _liferayPassword.isEmpty()) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Campaign scheduler skipped: liferay.email or " +
						"liferay.password is not configured");
			}

			return;
		}

		try {
			ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

			ZonedDateTime minuteStart = now.withSecond(0).withNano(0);
			ZonedDateTime minuteEnd = minuteStart.plusMinutes(1);

			String startStr = minuteStart.format(
				DateTimeFormatter.ISO_INSTANT);
			String endStr = minuteEnd.format(DateTimeFormatter.ISO_INSTANT);

			String filter = String.format(
				"scheduledSendDate ge %s and scheduledSendDate lt %s",
				startStr, endStr);

			String encodedFilter = URLEncoder.encode(
				filter, StandardCharsets.UTF_8.toString());

			RestTemplate restTemplate = _restTemplate;

			HttpHeaders headers = new HttpHeaders();

			String credentials = _liferayEmail + ":" + _liferayPassword;
			String encodedCredentials = Base64.getEncoder().encodeToString(
				credentials.getBytes(StandardCharsets.UTF_8));

			headers.set("Authorization", "Basic " + encodedCredentials);

			HttpEntity<String> entity = new HttpEntity<>(headers);

			ObjectMapper mapper = new ObjectMapper();

			int page = 1;
			boolean hasMorePages = true;
			boolean loggedSummary = false;

			while (hasMorePages) {
				String url = String.format(
					"%s/o/c/campaigns/?filter=%s&pageSize=100&page=%d",
					_liferayBaseUrl, encodedFilter, page);

				if (_log.isDebugEnabled()) {
					_log.debug(
						"Campaign scheduler querying page {}: {}", page,
						url);
				}

				ResponseEntity<String> response = restTemplate.exchange(
					URI.create(url), HttpMethod.GET, entity, String.class);

				JsonNode root = mapper.readTree(response.getBody());

				if (!loggedSummary) {
					int totalCount = root.path("totalCount").asInt(0);

					if (_log.isDebugEnabled()) {
						if (totalCount > 0) {
							_log.debug(
								"Discovered {} campaign(s) scheduled to " +
									"send RIGHT NOW ({})",
								totalCount, startStr);
						}
						else {
							_log.debug(
								"No campaigns scheduled for this " +
									"minute ({})",
								startStr);
						}
					}

					loggedSummary = true;
				}

				JsonNode items = root.path("items");

				if (items.isArray()) {
					for (JsonNode campaign : items) {
						String campaignId = campaign.path(
							"id").asText(null);

						// Honor the optional approval workflow the same way the
						// manual send path does. With no workflow linked every
						// entry is already Approved, so this only filters
						// campaigns an administrator has explicitly left
						// unapproved (Draft/Pending/Denied) but which happen to
						// carry a scheduledSendDate in this window.

						if (!_campaignExecutionService.isApproved(campaign)) {
							if (_log.isDebugEnabled()) {
								_log.debug(
									"Skipping scheduled campaign id={}: not in " +
										"Approved state",
									campaignId);
							}

							continue;
						}

						// Send-once guard: don't re-send a campaign that already
						// carries a lastSentDate. An administrator clears it to
						// deliberately send again.

						if (_campaignExecutionService.isAlreadySent(campaign)) {
							if (_log.isDebugEnabled()) {
								_log.debug(
									"Skipping scheduled campaign id={}: already " +
										"sent (lastSentDate is set)",
									campaignId);
							}

							continue;
						}

						String title = campaign.path(
							"title").asText(null);
						String objectDefinitionId = campaign.path(
							"objectDefinitionId").asText(null);
						String objectDefinitionTitle = campaign.path(
							"objectDefinitionTitle").asText(null);
						String objectEntryId = campaign.path(
							"objectEntryId").asText(null);
						String recipientUserGroupIds = campaign.path(
							"recipientUserGroupIds").asText(null);
						String recipientUserIds = campaign.path(
							"recipientUserIds").asText(null);
						String recipientEmailAddresses = campaign.path(
							"recipientEmailAddresses").asText(null);

						if (_log.isDebugEnabled()) {
							_log.debug(
								">>> Campaign id={} \"{}\" is DUE NOW " +
									"(scheduledSendDate={}) — executing",
								campaignId, title,
								campaign.path(
									"scheduledSendDate").asText());
						}

						String result =
							_campaignExecutionService.executeCampaign(
								headers, campaignId, title,
								objectDefinitionId, objectDefinitionTitle,
								objectEntryId, recipientUserGroupIds,
								recipientUserIds,
								recipientEmailAddresses);

						if (_log.isDebugEnabled()) {
							_log.debug(
								"Campaign id={} result: {}",
								campaignId, result);
						}
					}
				}

				JsonNode lastPageNode = root.path("lastPage");

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
		catch (Exception e) {
			_log.error("Campaign scheduler error: {}", e.getMessage(), e);
		}
	}

	private static final Logger _log = LoggerFactory.getLogger(
		CampaignScheduler.class);

	@Autowired
	private CampaignExecutionService _campaignExecutionService;

	@Autowired
	private RestTemplate _restTemplate;

	@Value("${liferay.base.url:}")
	private String _liferayBaseUrl;

	@Value("${liferay.email:}")
	private String _liferayEmail;

	@Value("${liferay.password:}")
	private String _liferayPassword;

}

