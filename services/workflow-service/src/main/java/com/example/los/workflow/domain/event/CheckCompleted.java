package com.example.los.workflow.domain.event;

import java.time.Instant;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.WorkflowId;

/** One external check produced a definitive answer. */
public record CheckCompleted(
        WorkflowId workflowId,
        String applicationId,
        CheckType checkType,
        CheckOutcome outcome,
        String reasonCode,
        Integer score,
        int attempt,
        long aggregateVersion,
        Instant occurredAt)
        implements WorkflowDomainEvent {}
