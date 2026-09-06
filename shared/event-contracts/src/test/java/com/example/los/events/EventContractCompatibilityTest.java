package com.example.los.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import com.example.los.events.application.ApplicationDecisionRecordedV1;
import com.example.los.events.application.ApplicationStatusChangedV1;
import com.example.los.events.application.ApplicationSubmittedV1;
import com.example.los.events.document.DocumentScanCompletedV1;
import com.example.los.events.document.DocumentUploadRequestedV1;
import com.example.los.events.workflow.WorkflowCheckCompletedV1;
import com.example.los.events.workflow.WorkflowCompletedV1;
import com.example.los.events.workflow.WorkflowFailedV1;
import com.example.los.events.workflow.WorkflowStartedV1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the wire compatibility of every released event contract.
 *
 * <p>The golden samples under {@code src/test/resources/golden/vN} are frozen
 * copies of what a producer emitted at version N. They are never edited. If a
 * change to a payload record stops a golden sample from deserialising, or drops
 * a value that used to be readable, the change is breaking and needs a new
 * schema version and a new topic rather than an edit to the existing one.
 *
 * <p>A schema registry (AWS Glue Schema Registry) is the eventual home for these
 * rules; see {@code docs/adr/ADR-0004}. Until then this suite is the enforcement
 * mechanism, and it runs on every pull request.
 */
class EventContractCompatibilityTest {

    private record GoldenSample(String file, Class<?> payloadType, String expectedEventType) {
        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<GoldenSample> releasedContracts() {
        return Stream.of(
                new GoldenSample(
                        "golden/v1/application.submitted.json",
                        ApplicationSubmittedV1.class,
                        EventTypes.APPLICATION_SUBMITTED),
                new GoldenSample(
                        "golden/v1/application.status-changed.json",
                        ApplicationStatusChangedV1.class,
                        EventTypes.APPLICATION_STATUS_CHANGED),
                new GoldenSample(
                        "golden/v1/application.decision-recorded.json",
                        ApplicationDecisionRecordedV1.class,
                        EventTypes.APPLICATION_DECISION_RECORDED),
                new GoldenSample(
                        "golden/v1/document.upload-requested.json",
                        DocumentUploadRequestedV1.class,
                        EventTypes.DOCUMENT_UPLOAD_REQUESTED),
                new GoldenSample(
                        "golden/v1/document.scan-completed.json",
                        DocumentScanCompletedV1.class,
                        EventTypes.DOCUMENT_SCAN_COMPLETED),
                new GoldenSample(
                        "golden/v1/workflow.started.json", WorkflowStartedV1.class, EventTypes.WORKFLOW_STARTED),
                new GoldenSample(
                        "golden/v1/workflow.check-completed.json",
                        WorkflowCheckCompletedV1.class,
                        EventTypes.WORKFLOW_CHECK_COMPLETED),
                new GoldenSample(
                        "golden/v1/workflow.completed.json", WorkflowCompletedV1.class, EventTypes.WORKFLOW_COMPLETED),
                new GoldenSample(
                        "golden/v1/workflow.failed.json", WorkflowFailedV1.class, EventTypes.WORKFLOW_FAILED));
    }

    @ParameterizedTest(name = "{0} still deserialises with the current contract")
    @MethodSource("releasedContracts")
    @DisplayName("every released golden sample still deserialises")
    void releasedSamplesStillDeserialise(GoldenSample sample) {
        String json = read(sample.file());

        EventEnvelope<?> envelope = EventJson.readEnvelope(json, sample.payloadType());

        assertThat(envelope.eventType()).isEqualTo(sample.expectedEventType());
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.payload()).isInstanceOf(sample.payloadType());
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.correlationId()).isNotBlank();
    }

    @ParameterizedTest(name = "{0} round-trips without losing a field")
    @MethodSource("releasedContracts")
    @DisplayName("re-serialising a golden sample preserves every field it carried")
    void roundTripPreservesEveryField(GoldenSample sample) {
        String original = read(sample.file());
        JsonNode originalTree = EventJson.mapper().readTree(original);

        EventEnvelope<?> envelope = EventJson.readEnvelope(original, sample.payloadType());
        JsonNode roundTripped = EventJson.mapper().readTree(EventJson.write(envelope));

        // Every field present in the released sample must survive a round trip
        // with the same value. Extra fields added later are allowed.
        assertFieldsPreserved(originalTree, roundTripped, "");
    }

    private static void assertFieldsPreserved(JsonNode expected, JsonNode actual, String path) {
        expected.propertyStream().forEach(entry -> {
            String field = entry.getKey();
            String childPath = path.isEmpty() ? field : path + "." + field;
            JsonNode expectedValue = entry.getValue();
            JsonNode actualValue = actual.get(field);

            assertThat(actualValue)
                    .withFailMessage(
                            "Field '%s' was present in the released contract and is missing after a round trip. "
                                    + "Removing or renaming a field is a breaking change: introduce a new schema "
                                    + "version and a new topic instead.",
                            childPath)
                    .isNotNull();

            if (expectedValue.isObject()) {
                assertFieldsPreserved(expectedValue, actualValue, childPath);
            } else {
                // Compared as raw JSON so arrays, nulls, numbers and strings are
                // all handled uniformly and a type change is caught as a value change.
                assertThat(actualValue.toString())
                        .withFailMessage(
                                "Field '%s' changed value across a round trip: released %s, now %s.",
                                childPath, expectedValue, actualValue)
                        .isEqualTo(expectedValue.toString());
            }
        });
    }

    @Test
    @DisplayName("a consumer tolerates fields added by a newer producer")
    void unknownFieldsAreTolerated() {
        // Simulates a producer that has been upgraded ahead of this consumer and
        // has started emitting an additional optional field. Additive change must
        // not break an older consumer; that property is what makes rolling
        // deployment of producers and consumers independent.
        String withNewField = read("golden/v1/application.submitted.json")
                .replace("\"productCode\": \"PL-STD-01\"", "\"productCode\": \"PL-STD-01\",\n    \"channelHint\": \"MOBILE\"");

        EventEnvelope<ApplicationSubmittedV1> envelope =
                EventJson.readEnvelope(withNewField, ApplicationSubmittedV1.class);

        assertThat(envelope.payload().productCode()).isEqualTo("PL-STD-01");
    }

    @Test
    @DisplayName("timestamps are ISO-8601 strings, never numeric epochs")
    void timestampsAreIso8601() {
        EventEnvelope<ApplicationStatusChangedV1> envelope = anEnvelope();

        String json = EventJson.write(envelope);

        assertThat(json).contains("\"occurredAt\":\"2026-01-15T09:30:02Z\"");
    }

    @Test
    @DisplayName("the envelope rejects an event without a correlation identifier")
    void envelopeRequiresCorrelationId() {
        assertThatThrownBy(() -> new EventEnvelope<>(
                        "evt-1",
                        EventTypes.APPLICATION_SUBMITTED,
                        1,
                        AggregateTypes.LOAN_APPLICATION,
                        "app-1",
                        1L,
                        Instant.parse("2026-01-15T09:30:02Z"),
                        "   ",
                        null,
                        Map.of(),
                        "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    @DisplayName("envelope metadata is defensively copied and immutable")
    void metadataIsImmutable() {
        EventEnvelope<ApplicationStatusChangedV1> envelope = anEnvelope();

        assertThatThrownBy(() -> envelope.metadata().put("injected", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("no released payload declares a field whose name suggests personal data")
    void payloadsCarryNoObviouslyPersonalFields() {
        // A blunt but effective guard. The privacy rule for the event stream is
        // that payloads carry pseudonyms and safe metadata only; this test makes
        // an accidental violation fail the build rather than reach a topic.
        List<String> forbidden = List.of(
                "firstname", "lastname", "surname", "fullname", "email", "phone", "mobile",
                "address", "street", "postcode", "zipcode", "dateofbirth", "dob", "ssn",
                "nationalid", "passport", "taxid", "iban", "accountnumber", "cardnumber");

        releasedContracts().forEach(sample -> {
            for (var component : sample.payloadType().getRecordComponents()) {
                String normalised = component.getName().toLowerCase(java.util.Locale.ROOT);
                assertThat(forbidden)
                        .withFailMessage(
                                "Payload %s declares field '%s', which looks like personal data. "
                                        + "Event payloads carry pseudonymous identifiers and safe metadata only.",
                                sample.payloadType().getSimpleName(), component.getName())
                        .noneMatch(normalised::contains);
            }
        });
    }

    private static EventEnvelope<ApplicationStatusChangedV1> anEnvelope() {
        return new EventEnvelope<>(
                "evt-1",
                EventTypes.APPLICATION_STATUS_CHANGED,
                ApplicationStatusChangedV1.SCHEMA_VERSION,
                AggregateTypes.LOAN_APPLICATION,
                "app-1",
                4L,
                Instant.parse("2026-01-15T09:30:02Z"),
                "corr-1",
                null,
                Map.of(EventMetadataKeys.PRODUCER, "application-service"),
                new ApplicationStatusChangedV1("app-1", "SUBMITTED", "VALIDATING", "SUBMISSION_ACCEPTED"));
    }

    private static String read(String resource) {
        try (InputStream in = EventContractCompatibilityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden sample: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read golden sample: " + resource, e);
        }
    }
}
