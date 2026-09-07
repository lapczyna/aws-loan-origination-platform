package com.example.los.audit.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Platform-wide beans for the audit context. */
@Configuration
class AuditConfig {

    /** UTC, injected rather than read through {@code Instant.now()}. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
