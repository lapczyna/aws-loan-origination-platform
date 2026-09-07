package com.example.los.audit.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One entry in the tamper-evident audit trail.
 *
 * <h2>The hash chain</h2>
 *
 * <p>Each record's hash is computed over its own canonical fields <em>and</em>
 * the previous record's hash. The records therefore form a chain in which
 * changing or removing any entry invalidates every hash after it.
 *
 * <p>This makes tampering <em>detectable</em>, not impossible. Anyone with
 * ownership of the database can rewrite rows, and if they recompute the whole
 * chain the result is internally consistent. What the chain buys is that they
 * must rewrite <em>everything after</em> the record they altered, and that the
 * result will not match any hash exported elsewhere — to the S3 audit bucket
 * with Object Lock, or to an auditor who recorded the head hash last quarter.
 * Silent, targeted edits become impossible; that is the property an auditor
 * needs, and it is achievable without trusting the database operator.
 *
 * <h2>Why the hash input is canonicalised</h2>
 *
 * <p>The summary is a map, and map iteration order is not guaranteed. Hashing it
 * in whatever order it happened to be built would produce a different hash for
 * the same record depending on the JVM, making verification fail at random and
 * indistinguishable from real tampering. Sorting the keys makes the hash a
 * function of the content alone.
 *
 * <h2>What a record may contain</h2>
 *
 * <p>Pseudonymous identifiers, event types, reason codes, statuses, timestamps.
 * Never a request body, document content, or an applicant's name, email address
 * or date of birth. An audit store is the longest-lived and most widely
 * replicated data on the platform; personal data placed here is personal data
 * nobody will ever successfully erase.
 */
public record AuditRecord(
        UUID id,
        String chainKey,
        long sequenceNumber,
        String sourceEventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        String subjectReference,
        Map<String, String> summary,
        String correlationId,
        Instant occurredAt,
        Instant recordedAt,
        String previousHash,
        String recordHash) {

    /**
     * ASCII unit separator, placed between fields when building the hash input.
     *
     * <p>Without a separator the field pairs {@code ("ab", "c")} and
     * {@code ("a", "bc")} would produce identical input and therefore identical
     * hashes, letting two genuinely different records collide. U+001F is chosen
     * because it cannot occur in any of the hashed values, which are all
     * identifiers, enum names or ISO-8601 timestamps.
     */
    private static final char FIELD_SEPARATOR = '\u001F';

    public AuditRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(chainKey, "chainKey must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        summary = summary == null ? Map.of() : Map.copyOf(summary);
    }

    /**
     * Appends a record to a chain, computing its hash from the predecessor's.
     *
     * @param previous the last record in this chain, or {@code null} to start one
     */
    public static AuditRecord append(
            String chainKey,
            AuditRecord previous,
            String sourceEventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String subjectReference,
            Map<String, String> summary,
            String correlationId,
            Instant occurredAt,
            Instant recordedAt) {

        long sequenceNumber = previous == null ? 1L : previous.sequenceNumber() + 1;
        String previousHash = previous == null ? null : previous.recordHash();

        UUID id = UUID.randomUUID();
        String hash = computeHash(
                id,
                chainKey,
                sequenceNumber,
                sourceEventId,
                eventType,
                schemaVersion,
                aggregateType,
                aggregateId,
                aggregateVersion,
                subjectReference,
                summary,
                correlationId,
                occurredAt,
                previousHash);

        return new AuditRecord(
                id,
                chainKey,
                sequenceNumber,
                sourceEventId,
                eventType,
                schemaVersion,
                aggregateType,
                aggregateId,
                aggregateVersion,
                subjectReference,
                summary,
                correlationId,
                occurredAt,
                recordedAt,
                previousHash,
                hash);
    }

    /**
     * Recomputes this record's hash and compares it with the stored value.
     *
     * <p>Detects a record that was altered in place. It does not detect a record
     * that was removed entirely — that is what verifying the chain's links
     * catches, since the successor's {@code previousHash} will no longer match.
     */
    public boolean hasIntactHash() {
        String recomputed = computeHash(
                id,
                chainKey,
                sequenceNumber,
                sourceEventId,
                eventType,
                schemaVersion,
                aggregateType,
                aggregateId,
                aggregateVersion,
                subjectReference,
                summary,
                correlationId,
                occurredAt,
                previousHash);
        // Constant-time comparison. The margin it buys is small here, but a
        // verification routine that leaks timing is a bad habit to establish.
        return MessageDigest.isEqual(
                recomputed.getBytes(StandardCharsets.UTF_8), recordHash.getBytes(StandardCharsets.UTF_8));
    }

    /** True when this record correctly follows the given predecessor. */
    public boolean follows(AuditRecord previous) {
        if (previous == null) {
            return sequenceNumber == 1L && previousHash == null;
        }
        return sequenceNumber == previous.sequenceNumber() + 1
                && Objects.equals(previousHash, previous.recordHash())
                && Objects.equals(chainKey, previous.chainKey());
    }

    /**
     * Deliberately does not print the summary.
     *
     * <p>The summary is safe by construction, but an audit record reaching a log
     * statement duplicates the trail into a second, less protected store, which
     * defeats the point of having a controlled one.
     */
    @Override
    public String toString() {
        return "AuditRecord[chainKey=" + chainKey + ", sequence=" + sequenceNumber + ", eventType=" + eventType + "]";
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static String computeHash(
            UUID id,
            String chainKey,
            long sequenceNumber,
            String sourceEventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String subjectReference,
            Map<String, String> summary,
            String correlationId,
            Instant occurredAt,
            String previousHash) {

        StringBuilder canonical = new StringBuilder();
        appendField(canonical, id.toString());
        appendField(canonical, chainKey);
        appendField(canonical, Long.toString(sequenceNumber));
        appendField(canonical, sourceEventId);
        appendField(canonical, eventType);
        appendField(canonical, Integer.toString(schemaVersion));
        appendField(canonical, aggregateType);
        appendField(canonical, aggregateId);
        appendField(canonical, Long.toString(aggregateVersion));
        appendField(canonical, subjectReference);
        appendField(canonical, correlationId);
        appendField(canonical, occurredAt.toString());
        appendField(canonical, previousHash);

        // Sorted, so the hash depends on the content and not on map iteration
        // order, which would otherwise vary and look exactly like tampering.
        new TreeMap<>(summary).forEach((key, value) -> {
            appendField(canonical, key);
            appendField(canonical, value);
        });

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // A JVM without SHA-256 cannot produce an audit trail at all, and
            // continuing without one would be worse than failing.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    private static void appendField(StringBuilder canonical, String value) {
        canonical.append(value == null ? "" : value).append(FIELD_SEPARATOR);
    }
}
