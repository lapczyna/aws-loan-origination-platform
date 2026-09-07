package com.example.los.e2e;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import com.example.los.events.Topics;
import com.example.los.testsupport.PlatformContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the broker misbehaves.
 *
 * <p>Kafka guarantees at-least-once delivery, not exactly-once. Every consumer
 * here is therefore written to be idempotent, and these tests check that claim
 * by doing to the platform what a broker does on a bad day: delivering the same
 * event twice, delivering an old event after a newer one, and going away in the
 * middle of a submission.
 *
 * <p>The duplicated events are not hand-written. They are read back out of the
 * outbox, which stores the exact serialised envelope that was published, and
 * republished verbatim — so the test cannot drift from the real wire format the
 * way a hand-built fixture would.
 */
class EventDeliveryIT extends AbstractEndToEndTest {

    @Test
    @DisplayName("the same submission event delivered twice starts exactly one assessment")
    void aDuplicatedSubmissionEventStartsOneAssessment() {
        String applicationId = submitAndReachDecision();

        String envelope = queryString(
                "SELECT payload::text FROM application.outbox_event "
                        + "WHERE aggregate_id = ? AND event_type = 'application.submitted'",
                applicationId);
        assertThat(envelope).isNotBlank();

        long workflowsBefore = countRows(
                "SELECT count(*) FROM workflow.workflow_instance WHERE application_id = ?::uuid", applicationId);
        assertThat(workflowsBefore).isEqualTo(1);

        // The broker redelivers. Same event id, same bytes.
        publish(Topics.APPLICATION_EVENTS, applicationId, envelope);
        publish(Topics.APPLICATION_EVENTS, applicationId, envelope);

        // Nothing to wait FOR, so wait for the absence of an effect: the
        // de-duplication ledger must have swallowed both copies.
        Awaitility.await("the duplicates are consumed and ignored")
                .during(Duration.ofSeconds(3))
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(countRows(
                                "SELECT count(*) FROM workflow.workflow_instance WHERE application_id = ?::uuid",
                                applicationId))
                        .isEqualTo(1));

        // And the audit trail did not grow a second copy of the same event.
        assertThat(countRows(
                        "SELECT count(*) FROM audit.audit_record "
                                + "WHERE chain_key = ? AND event_type = 'application.submitted'",
                        applicationId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an outcome event redelivered after the decision does not change the decision")
    void aRedeliveredOutcomeDoesNotChangeTheDecision() {
        String applicationId = submitAndReachDecision();

        String decisionBefore =
                queryString("SELECT decision_type FROM application.loan_application WHERE id = ?::uuid", applicationId);
        assertThat(decisionBefore).isNotBlank();

        // partition_key, not aggregate_id: a workflow event's aggregate is the
        // WORKFLOW, and the application identifier is the partition key, because
        // consumers order per application rather than per workflow.
        String completed = queryString(
                "SELECT payload::text FROM workflow.outbox_event "
                        + "WHERE partition_key = ? AND event_type = 'workflow.completed'",
                applicationId);
        assertThat(completed).isNotBlank();

        // Arriving late, after the application has already moved on. A consumer
        // that applied it again would re-run a decision that has already been
        // recorded and, worse, emit a second decision event.
        publish(Topics.WORKFLOW_EVENTS, applicationId, completed);

        Awaitility.await("the stale outcome changes nothing")
                .during(Duration.ofSeconds(3))
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> {
                    assertThat(queryString(
                                    "SELECT decision_type FROM application.loan_application WHERE id = ?::uuid",
                                    applicationId))
                            .isEqualTo(decisionBefore);
                    assertThat(countRows(
                                    "SELECT count(*) FROM application.outbox_event "
                                            + "WHERE aggregate_id = ? AND event_type = 'application.decision-recorded'",
                                    applicationId))
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("a broker outage holds events in the outbox and publication resumes on recovery")
    void aBrokerOutageDoesNotLoseEvents() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));
        uploadBothMandatoryDocuments(applicationId, token);

        pauseKafka();
        try {
            // Submission still succeeds. The event is written in the SAME
            // transaction as the state change, so accepting the submission never
            // depends on the broker being reachable. That is the whole point of
            // the outbox: an unavailable broker must not become an unavailable API.
            HttpResponse<String> submitted = post(
                    platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit",
                    token,
                    null,
                    "outage-" + suffix);

            assertThat(submitted.statusCode())
                    .withFailMessage("Submission failed while the broker was down: %s", submitted.body())
                    .isEqualTo(202);

            // The event is durable and unpublished: not lost, not published, not
            // silently dropped. Asserted as something that HOLDS for a few
            // seconds rather than something that is momentarily true -- the
            // publisher polls five times a second, so a single check could catch
            // a gap between attempts and prove nothing. Holding also confirms the
            // broker really is paused, which is the premise of the whole test.
            Awaitility.await("the submission event stays in the outbox while the broker is down")
                    .during(Duration.ofSeconds(4))
                    .atMost(PlatformUnderTest.patience())
                    // The submission event specifically, not a count of everything
                    // pending: submitting emits several events, and asserting on
                    // the total would break the next time one is added.
                    .untilAsserted(() -> assertThat(countRows(
                                    "SELECT count(*) FROM application.outbox_event WHERE aggregate_id = ? "
                                            + "AND event_type = 'application.submitted' AND status = 'PENDING'",
                                    applicationId))
                            .isEqualTo(1));

            // And no decision was reached, because nothing could reach the
            // workflow context.
            assertThat(queryString("SELECT decision_type FROM application.loan_application WHERE id = ?::uuid",
                            applicationId))
                    .isNull();
        } finally {
            unpauseKafka();
        }

        // On recovery the publisher drains what it was holding and the journey
        // completes with no intervention.
        Awaitility.await("the assessment completes once the broker is back")
                .atMost(Duration.ofMinutes(2))
                .untilAsserted(() -> assertThat(queryString(
                                "SELECT status FROM application.loan_application WHERE id = ?::uuid", applicationId))
                        .isEqualTo("APPROVED"));

        // Waited for, not asserted immediately. Reaching APPROVED and having
        // published every event are two different moments: the decision is
        // recorded in a transaction that also writes an outbox row, and that row
        // is published on the next poll. Asserting straight after the status
        // check passes on a quiet machine and fails under load -- which is
        // exactly what it did the first time the full suite ran.
        Awaitility.await("the recovered outbox drains completely")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(countRows(
                                "SELECT count(*) FROM application.outbox_event "
                                        + "WHERE aggregate_id = ? AND status <> 'PUBLISHED'",
                                applicationId))
                        .isZero());
    }

    // -------------------------------------------------------------------------

    private String submitAndReachDecision() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));
        uploadBothMandatoryDocuments(applicationId, token);

        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit",
                token,
                null,
                "submit-" + suffix);
        assertThat(submitted.statusCode()).isEqualTo(202);

        Awaitility.await("a decision is reached")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(queryString(
                                "SELECT status FROM application.loan_application WHERE id = ?::uuid", applicationId))
                        .isEqualTo("APPROVED"));

        return applicationId;
    }

    /** Publishes a raw envelope, keyed by application id exactly as the services key theirs. */
    private static void publish(String topic, String key, String envelope) {
        Properties configuration = new Properties();
        configuration.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                PlatformContainers.kafka().getBootstrapServers());
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(configuration)) {
            producer.send(new ProducerRecord<>(topic, key, envelope));
            producer.flush();
        }
    }

    /**
     * Pauses the broker's container.
     *
     * <p>Paused rather than stopped, deliberately. Stopping and restarting would
     * give the container a new port mapping, and every service is holding a
     * bootstrap address pointing at the old one — the test would then be
     * measuring how the services cope with a broker that moved, which is a
     * different and much rarer failure. A pause freezes the process with its
     * sockets intact, which is what a broker under a network partition or a long
     * GC actually looks like from a client's side.
     */
    private static void pauseKafka() {
        DockerClientFactory.instance()
                .client()
                .pauseContainerCmd(PlatformContainers.kafka().getContainerId())
                .exec();
    }

    private static void unpauseKafka() {
        DockerClientFactory.instance()
                .client()
                .unpauseContainerCmd(PlatformContainers.kafka().getContainerId())
                .exec();
    }
}
