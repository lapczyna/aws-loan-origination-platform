package com.example.los.audit.adapter.in.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import com.example.los.audit.domain.model.AuditSummary;
import com.example.los.audit.usecase.service.RecordAuditEntryUseCase;
import com.example.los.events.EventJson;

/**
 * Records every business event the platform publishes.
 *
 * <p>Subscribes to all three business topics, because the audit trail's purpose
 * is to answer "what happened to this application, everywhere" and a trail that
 * covered only one context could not.
 *
 * <h2>Why this reads the payload generically</h2>
 *
 * <p>It parses the envelope into a tree rather than binding to concrete payload
 * types. Binding would force this service to be redeployed in lockstep with every
 * other context: a new event type, or a new field on an existing one, would fail
 * to deserialise and stall the audit trail — the one component that most needs to
 * keep recording during a change. Reading generically means an unrecognised event
 * is still recorded, with whatever allow-listed fields it carries.
 *
 * <h2>The allow-list is applied here</h2>
 *
 * <p>Only fields named in {@link AuditSummary} are copied into the summary.
 * Everything else in the payload is discarded. That is what makes generic reading
 * safe: a future producer that adds a field carrying personal data cannot get it
 * into the trail by accident, because nothing copies a field nobody allow-listed.
 */
@Component
class BusinessEventListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventListener.class);

    /**
     * Payload fields carrying the applicant's pseudonym.
     *
     * <p>Only these are lifted into {@code subjectReference}. Anything else that
     * happens to identify a subject stays out of the trail.
     */
    private static final String APPLICANT_REFERENCE_FIELD = "applicantReference";

    private final RecordAuditEntryUseCase recording;

    BusinessEventListener(RecordAuditEntryUseCase recording) {
        this.recording = recording;
    }

    @KafkaListener(
            topics = {
                "#{T(com.example.los.events.Topics).APPLICATION_EVENTS}",
                "#{T(com.example.los.events.Topics).DOCUMENT_EVENTS}",
                "#{T(com.example.los.events.Topics).WORKFLOW_EVENTS}"
            },
            groupId = "${los.kafka.consumer-group.audit:audit-service.all-events}")
    public void onBusinessEvent(String message) {
        EventJson.EventHeader header = EventJson.readHeader(message);
        JsonNode payload = EventJson.mapper().readTree(message).get("payload");

        RecordAuditEntryUseCase.Outcome outcome = recording.record(new RecordAuditEntryUseCase.AuditEntry(
                header.eventId(),
                header.eventType(),
                header.schemaVersion(),
                header.aggregateType(),
                // The chain is keyed on the APPLICATION, not on the aggregate that
                // produced the event. An auditor asks "what happened to this
                // application", and chaining per producing aggregate would scatter
                // that answer across three separate chains.
                applicationIdOf(payload, header),
                header.aggregateVersion(),
                subjectReferenceOf(payload),
                safeSummaryOf(payload),
                header.correlationId(),
                header.occurredAt()));

        log.debug("Audited event. eventType={} outcome={}", header.eventType(), outcome);
    }

    /**
     * The application this event concerns.
     *
     * <p>Every business payload on the platform carries {@code applicationId}.
     * Falling back to the aggregate identifier keeps an unfamiliar event
     * recordable rather than dropped: a partial audit entry is far better than a
     * silent gap in the trail.
     */
    private static String applicationIdOf(JsonNode payload, EventJson.EventHeader header) {
        if (payload != null && payload.has("applicationId")) {
            return payload.get("applicationId").asString();
        }
        return header.aggregateId();
    }

    private static String subjectReferenceOf(JsonNode payload) {
        if (payload != null && payload.has(APPLICANT_REFERENCE_FIELD)) {
            return payload.get(APPLICANT_REFERENCE_FIELD).asString();
        }
        return null;
    }

    /**
     * Copies only the allow-listed scalar fields out of the payload.
     *
     * <p>Objects and arrays are skipped entirely: a nested structure is exactly
     * where an unnoticed field would hide, and no allow-listed key is structured.
     */
    private static Map<String, String> safeSummaryOf(JsonNode payload) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (payload == null || !payload.isObject()) {
            return summary;
        }

        payload.propertyStream().forEach(property -> {
            String key = property.getKey();
            JsonNode value = property.getValue();
            if (!AuditSummary.isAllowed(key) || value == null || value.isNull()) {
                return;
            }
            if (value.isObject() || value.isArray()) {
                return;
            }
            summary.put(key, value.asString());
        });

        return summary;
    }
}
