package com.example.los.application.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.observability.CorrelationId;
import com.example.los.application.usecase.service.RecordAssessmentOutcomeUseCase;
import com.example.los.events.EventJson;
import com.example.los.events.EventTypes;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.events.workflow.WorkflowCompletedV1;
import com.example.los.events.workflow.WorkflowFailedV1;

/**
 * Applies workflow outcomes to the application.
 *
 * <p>A thin adapter. It parses the envelope, propagates the correlation
 * identifier so the resulting log lines and events join the same business
 * interaction, and delegates. All the de-duplication, staleness and state
 * checking lives in {@link RecordAssessmentOutcomeUseCase}, where it can be
 * tested without a broker.
 */
@Component
class WorkflowEventListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final RecordAssessmentOutcomeUseCase outcomes;

    WorkflowEventListener(RecordAssessmentOutcomeUseCase outcomes) {
        this.outcomes = outcomes;
    }

    @KafkaListener(
            topics = "#{T(com.example.los.events.Topics).WORKFLOW_EVENTS}",
            groupId = "${los.kafka.consumer-group.workflow-events:application-service.workflow-events}")
    public void onWorkflowEvent(String message) {
        EventJson.EventHeader header = EventJson.readHeader(message);

        // The correlation identifier travels with the event, so work triggered by
        // a customer's original request stays traceable to it across services.
        CorrelationId.set(header.correlationId());
        try {
            handle(header, message);
        } finally {
            CorrelationId.clear();
        }
    }

    private void handle(EventJson.EventHeader header, String message) {
        switch (header.eventType()) {
            case EventTypes.WORKFLOW_COMPLETED -> {
                WorkflowCompletedV1 payload =
                        EventJson.readEnvelope(message, WorkflowCompletedV1.class).payload();
                outcomes.recordWorkflowRecommendation(
                        header.eventId(),
                        ApplicationId.of(payload.applicationId()),
                        Recommendation.valueOf(payload.recommendation()),
                        payload.reasonCode(),
                        header.aggregateVersion(),
                        header.correlationId());
            }
            case EventTypes.WORKFLOW_FAILED -> {
                WorkflowFailedV1 payload =
                        EventJson.readEnvelope(message, WorkflowFailedV1.class).payload();
                outcomes.recordWorkflowFailure(
                        header.eventId(),
                        ApplicationId.of(payload.applicationId()),
                        payload.errorCode(),
                        header.correlationId());
            }
            // workflow.started and workflow.check-completed are progress
            // information. This service acts only on outcomes, so they are
            // acknowledged and ignored rather than stalling the partition.
            default -> log.debug("Ignoring workflow progress event. eventType={}", header.eventType());
        }
    }
}
