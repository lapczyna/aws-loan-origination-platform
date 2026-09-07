package com.example.los.workflow.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.los.workflow.domain.model.AssessmentPolicy;

/** Beans for the domain policy and the platform clock. */
@Configuration
class WorkflowConfig {

    /**
     * UTC, injected everywhere rather than read through {@code Instant.now()}.
     *
     * <p>Tests can then assert on exact timestamps, and every event the platform
     * records is UTC by construction rather than by the accident of where the pod
     * was scheduled.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The decision policy.
     *
     * <p>A bean rather than a static call so that a different lender's rules can
     * be supplied without touching the workflow engine, and so the policy can be
     * exercised in isolation.
     */
    @Bean
    AssessmentPolicy assessmentPolicy() {
        return new AssessmentPolicy();
    }
}
