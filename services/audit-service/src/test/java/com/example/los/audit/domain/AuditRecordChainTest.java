package com.example.los.audit.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.los.audit.domain.model.AuditRecord;
import com.example.los.audit.domain.model.AuditSummary;
import com.example.los.audit.domain.model.UnsafeAuditSummaryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hash chain and the summary allow-list.
 *
 * <p>These two mechanisms are the audit trail's entire value proposition, so
 * they get tested against the ways they would actually be attacked rather than
 * only against the happy path.
 */
class AuditRecordChainTest {

    private static final Instant OCCURRED = Instant.parse("2026-01-15T09:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-01-15T09:30:01Z");

    @Test
    @DisplayName("the first record in a chain has no predecessor")
    void firstRecordStartsTheChain() {
        AuditRecord first = record(null, "evt-1", Map.of("status", "SUBMITTED"));

        assertThat(first.sequenceNumber()).isEqualTo(1L);
        assertThat(first.previousHash()).isNull();
        assertThat(first.recordHash()).isNotBlank();
        assertThat(first.follows(null)).isTrue();
    }

    @Test
    @DisplayName("each record links to its predecessor")
    void recordsLinkToTheirPredecessor() {
        AuditRecord first = record(null, "evt-1", Map.of("status", "SUBMITTED"));
        AuditRecord second = record(first, "evt-2", Map.of("status", "VALIDATING"));

        assertThat(second.sequenceNumber()).isEqualTo(2L);
        assertThat(second.previousHash()).isEqualTo(first.recordHash());
        assertThat(second.follows(first)).isTrue();
    }

    @Test
    @DisplayName("altering a record invalidates its own hash")
    void alteringARecordIsDetected() {
        // Someone editing a decision from REJECTED to APPROVED directly in the
        // database. The record's stored hash no longer matches its content.
        AuditRecord original = record(null, "evt-1", Map.of("decision", "REJECTED"));

        AuditRecord tampered = new AuditRecord(
                original.id(),
                original.chainKey(),
                original.sequenceNumber(),
                original.sourceEventId(),
                original.eventType(),
                original.schemaVersion(),
                original.aggregateType(),
                original.aggregateId(),
                original.aggregateVersion(),
                original.subjectReference(),
                Map.of("decision", "APPROVED"),
                original.correlationId(),
                original.occurredAt(),
                original.recordedAt(),
                original.previousHash(),
                // The attacker keeps the original hash, because recomputing it
                // would break every record after this one.
                original.recordHash());

        assertThat(original.hasIntactHash()).isTrue();
        assertThat(tampered.hasIntactHash()).isFalse();
    }

    @Test
    @DisplayName("recomputing a tampered record's hash breaks the link to its successor")
    void recomputingAHashBreaksTheChain() {
        // The other half of the attack: the tamperer recomputes the hash so the
        // record verifies on its own. The successor still points at the OLD hash,
        // so the chain no longer links up.
        AuditRecord first = record(null, "evt-1", Map.of("decision", "REJECTED"));
        AuditRecord second = record(first, "evt-2", Map.of("status", "REJECTED"));

        AuditRecord rewritten = AuditRecord.append(
                first.chainKey(),
                null,
                first.sourceEventId(),
                first.eventType(),
                first.schemaVersion(),
                first.aggregateType(),
                first.aggregateId(),
                first.aggregateVersion(),
                first.subjectReference(),
                Map.of("decision", "APPROVED"),
                first.correlationId(),
                first.occurredAt(),
                first.recordedAt());

        // Internally consistent...
        assertThat(rewritten.hasIntactHash()).isTrue();
        // ...but the successor no longer follows it. Tampering with one record
        // forces rewriting every record after it, which is the property that
        // makes silent, targeted edits impossible.
        assertThat(second.follows(rewritten)).isFalse();
    }

    @Test
    @DisplayName("removing a record breaks the sequence")
    void removingARecordIsDetected() {
        AuditRecord first = record(null, "evt-1", Map.of("status", "SUBMITTED"));
        AuditRecord second = record(first, "evt-2", Map.of("status", "VALIDATING"));
        AuditRecord third = record(second, "evt-3", Map.of("status", "APPROVED"));

        // Delete the middle record and the third no longer follows the first.
        assertThat(third.follows(first)).isFalse();
    }

    @Test
    @DisplayName("verification does not depend on the order the summary was built in")
    void hashIsIndependentOfSummaryIteration() {
        // Without canonicalisation this would fail intermittently and look
        // exactly like tampering, which is the worst possible failure mode for a
        // verification routine.
        Map<String, String> asWritten = new LinkedHashMap<>();
        asWritten.put("status", "APPROVED");
        asWritten.put("reasonCode", "ALL_CHECKS_PASSED");
        asWritten.put("decision", "APPROVED");

        AuditRecord written = record(null, "evt-1", asWritten);

        // The same record read back, with its summary rebuilt in a different
        // order -- which is what happens when it comes out of JSONB.
        Map<String, String> asRead = new LinkedHashMap<>();
        asRead.put("decision", "APPROVED");
        asRead.put("status", "APPROVED");
        asRead.put("reasonCode", "ALL_CHECKS_PASSED");

        AuditRecord readBack = new AuditRecord(
                written.id(),
                written.chainKey(),
                written.sequenceNumber(),
                written.sourceEventId(),
                written.eventType(),
                written.schemaVersion(),
                written.aggregateType(),
                written.aggregateId(),
                written.aggregateVersion(),
                written.subjectReference(),
                asRead,
                written.correlationId(),
                written.occurredAt(),
                written.recordedAt(),
                written.previousHash(),
                written.recordHash());

        assertThat(readBack.hasIntactHash()).isTrue();
    }

    @Test
    @DisplayName("two records differing only in field boundaries hash differently")
    void fieldsCannotRunTogether() {
        // Without a separator, ("ab","c") and ("a","bc") would produce identical
        // hash input and two genuinely different records would collide.
        AuditRecord first = record(null, "evt-1", Map.of("status", "ABC"));
        AuditRecord second = AuditRecord.append(
                "chain-1",
                null,
                "evt-1",
                "application.status-changed",
                1,
                "LoanApplication",
                "app-1",
                1L,
                "APR-0000000000000000",
                Map.of("status", "AB", "reasonCode", "C"),
                "corr-1",
                OCCURRED,
                RECORDED);

        assertThat(first.recordHash()).isNotEqualTo(second.recordHash());
    }

    @Test
    @DisplayName("a summary key that is not on the allow-list is refused")
    void unknownSummaryKeysAreRefused() {
        // The control that keeps personal data out of the longest-lived store on
        // the platform. An allow-list fails loudly; a deny-list would not fail at
        // all for a field nobody thought of.
        assertThatThrownBy(() -> AuditSummary.requireSafe(Map.of("applicantEmail", "someone@example.com")))
                .isInstanceOf(UnsafeAuditSummaryException.class)
                .hasMessageContaining("applicantEmail")
                // The message names the key, never the value.
                .hasMessageNotContaining("someone@example.com");
    }

    @Test
    @DisplayName("an over-long summary value is refused")
    void longSummaryValuesAreRefused() {
        // An allowed key holding prose is still a problem: it is exactly how
        // free-form content reaches a field meant only for a code.
        String prose = "x".repeat(AuditSummary.MAXIMUM_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> AuditSummary.requireSafe(Map.of("reasonCode", prose)))
                .isInstanceOf(UnsafeAuditSummaryException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    @DisplayName("every allow-listed key is a code, never a free-text or identifying field")
    void allowListContainsNoIdentifyingFields() {
        // A guard on the allow-list itself: adding "applicantName" here would be
        // a one-line change that quietly broke the platform's central privacy
        // promise, so the test refuses names that look identifying.
        for (String key : AuditSummary.allowedKeys()) {
            String normalised = key.toLowerCase(java.util.Locale.ROOT);
            assertThat(normalised)
                    .withFailMessage("Allow-listed audit key '%s' looks like it could carry personal data", key)
                    .doesNotContain("name")
                    .doesNotContain("email")
                    .doesNotContain("phone")
                    .doesNotContain("address")
                    .doesNotContain("birth")
                    .doesNotContain("note")
                    .doesNotContain("comment")
                    .doesNotContain("amount")
                    .doesNotContain("income");
        }
    }

    @Test
    @DisplayName("a record never prints its summary")
    void toStringDoesNotPrintTheSummary() {
        // An audit record reaching a log statement duplicates the trail into a
        // second, less protected store.
        AuditRecord record = record(null, "evt-1", Map.of("decision", "REJECTED"));

        assertThat(record.toString()).contains("chain-1").doesNotContain("REJECTED");
    }

    // --- fixtures ------------------------------------------------------------

    private static AuditRecord record(AuditRecord previous, String eventId, Map<String, String> summary) {
        return AuditRecord.append(
                "chain-1",
                previous,
                eventId,
                "application.status-changed",
                1,
                "LoanApplication",
                "app-1",
                1L,
                "APR-0000000000000000",
                summary,
                "corr-1",
                OCCURRED,
                RECORDED);
    }
}
