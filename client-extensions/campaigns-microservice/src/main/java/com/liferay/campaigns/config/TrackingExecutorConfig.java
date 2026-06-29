// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor that keeps the public, unauthenticated
 * {@code /tracking-pixel} endpoint responsive while it fans out fire-and-forget
 * analytics work.
 *
 * <p>The pixel endpoint is intentionally reachable anonymously (email clients
 * call it), so it leans on this bounded pool + bounded queue rather than
 * authentication to stay within a fixed resource budget. The cap means a burst
 * of pixel requests does a predictable, bounded amount of work instead of
 * spawning an unbounded number of threads (the previous
 * {@code new Thread(...).start()} per request). Once the pool and queue are
 * full, further tracking tasks are rejected (and dropped by the caller),
 * trading a few analytics events under extreme load for a service that stays
 * alive and responsive.</p>
 *
 * @author Neil Griffin
 */
@Configuration
public class TrackingExecutorConfig {

    public static final String TRACKING_EXECUTOR = "trackingExecutor";

    @Bean(name = TRACKING_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor trackingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("tracking-pixel-");
        executor.setKeepAliveSeconds(60);

        // Reject (don't block the web thread, don't grow unbounded) when
        // saturated; the controller catches the rejection and drops the event.
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.AbortPolicy());

        executor.initialize();

        return executor;
    }

}
