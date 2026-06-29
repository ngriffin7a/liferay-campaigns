// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Provides a single, application-wide {@link RestTemplate} configured with
 * connect and read timeouts.
 *
 * <p>The services and scheduler make synchronous outbound calls to Liferay and
 * Analytics Cloud. Previously each created a {@code new RestTemplate()} with
 * default (infinite) timeouts, so a slow or hung upstream could block the
 * caller indefinitely -- pinning the scheduler thread or the bounded
 * tracking-pixel pool. Bounding both timeouts ensures stuck calls fail fast and
 * release their thread.</p>
 *
 * <p>{@link RestTemplate} is thread-safe once built, so a single shared bean is
 * injected wherever outbound HTTP is needed.</p>
 *
 * @author Neil Griffin
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory =
            new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(_connectTimeoutMillis);
        factory.setReadTimeout(_readTimeoutMillis);

        return new RestTemplate(factory);
    }

    @Value("${rest.client.connect.timeout.millis:5000}")
    private int _connectTimeoutMillis;

    @Value("${rest.client.read.timeout.millis:10000}")
    private int _readTimeoutMillis;

}
