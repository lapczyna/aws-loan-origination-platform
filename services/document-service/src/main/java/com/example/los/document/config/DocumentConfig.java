package com.example.los.document.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Platform-wide beans for the document context. */
@Configuration
class DocumentConfig {

    /** UTC, injected rather than read through {@code Instant.now()}. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
