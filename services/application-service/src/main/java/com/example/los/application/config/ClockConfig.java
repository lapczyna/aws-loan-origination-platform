package com.example.los.application.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's clock.
 *
 * <p>A bean rather than {@code Instant.now()} scattered through the code, for
 * two reasons. Tests can substitute a fixed clock and assert on exact timestamps
 * instead of tolerating a window. And every timestamp the platform records is UTC
 * by construction: a service that reads the container's local time zone produces
 * events whose ordering depends on where the pod was scheduled.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
