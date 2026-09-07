package com.example.los.workflow.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.los.events.EventJson;
import com.example.los.events.EventTypes;
import com.example.los.events.application.ApplicationSubmittedV1;
import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.usecase.service.StartAssessmentUseCase;

/**
 * Starts an assessment when an application is submitted.
 *
 * <p>A thin adapter: it parses the envelope and delegates. The de-duplication and
 * the "already assessing" guard live in the use case, where they can be tested
 * without a broker.
 *
 * <p>Event types this service does not act on are acknowledged and ignored rather
 * than throwing, because a listener that fails on an unrecognised event stalls
 * the partition for every event queued behind it — including the ones it does
 * understand.
 */
@Component
class ApplicationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationEventListener.class);

    private final StartAssessmentUseCase startAssessment;

    ApplicationEventListener(StartAssessmentUseCase startAssessment) {
        this.startAssessment = startAssessment;
    }

    @KafkaListener(
            topics = "#{T(com.example.los.events.Topics).APPLICATION_EVENTS}",
            groupId = "${los.kafka.consumer-group.application-events:workflow-service.application-events}")
    public void onApplicationEvent(String message) {
        EventJson.EventHeader header = EventJson.readHeader(message);

        if (!EventTypes.APPLICATION_SUBMITTED.equals(header.eventType())) {
            log.debug("Ignoring application event this service does not act on. eventType={}", header.eventType());
            return;
        }

        ApplicationSubmittedV1 payload =
                EventJson.readEnvelope(message, ApplicationSubmittedV1.class).payload();

        StartAssessmentUseCase.Outcome outcome = startAssessment.start(
                header.eventId(),
                payload.applicationId(),
                payload.applicantReference(),
                new AssessmentInputs(
                        payload.amountMinorUnits(),
                        payload.currency(),
                        payload.termMonths(),
                        payload.purpose(),
                        payload.declaredAnnualIncomeMinorUnits(),
                        payload.productCode()),
                header.correlationId());

        log.debug(
                "Handled submission event. applicationId={} outcome={} correlationId={}",
                payload.applicationId(),
                outcome,
                header.correlationId());
    }
}
