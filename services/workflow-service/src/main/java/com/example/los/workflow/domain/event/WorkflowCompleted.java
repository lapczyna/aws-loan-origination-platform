package com.example.los.workflow.domain.event;

import java.time.Instant;

import com.example.los.events.vocabulary.Recommendation;
import com.example.los.workflow.domain.model.WorkflowId;

/** Every check answered and the policy produced a recommendation. */
public record WorkflowCompleted(
        WorkflowId workflowId,
        String applicationId,
        Recommendation recommendation,
        String reasonCode,
        long aggregateVersion,
        Instant occurredAt)
        implements WorkflowDomainEvent {}
