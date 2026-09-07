package com.example.los.workflow.domain.event;

import java.time.Instant;

import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.WorkflowId;

/**
 * A check exhausted its retry budget and the assessment was abandoned.
 *
 * <p>Technical, not commercial: nothing has been decided about the applicant.
 */
public record WorkflowFailed(
        WorkflowId workflowId,
        String applicationId,
        CheckType failedCheck,
        String errorCode,
        int attempts,
        long aggregateVersion,
        Instant occurredAt)
        implements WorkflowDomainEvent {}
