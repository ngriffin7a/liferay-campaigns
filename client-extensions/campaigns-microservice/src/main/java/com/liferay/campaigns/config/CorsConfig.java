// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.config;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures CORS for the microservice endpoints called from the campaigns
 * fragment.
 *
 * <p>The fragment makes cross-origin requests to the microservice when
 * {@code bypassSameOrigin} is enabled (local dev). Allowed origins are
 * controlled by the {@code CORS_ALLOWED_ORIGIN_PATTERNS} environment variable
 * (defaults to {@code *} for local dev; scope to {@code https://*.lfr.cloud}
 * for PaaS).</p>
 *
 * @author Neil Griffin
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(
                Objects.requireNonNull(_allowedOriginPatterns.split(",")))
            .allowedMethods("GET", "OPTIONS", "POST");
    }

    @Value("${cors.allowed.origin.patterns:*}")
    private String _allowedOriginPatterns = "*";

}
