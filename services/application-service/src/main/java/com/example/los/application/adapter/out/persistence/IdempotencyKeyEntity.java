package com.example.los.application.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A recorded request outcome, keyed by the client's idempotency key.
 *
 * <p>The stored response body is the API representation, which by construction
 * carries no personal data: it identifies the application and reports its
 * status. That is what makes it safe to keep for the retention window and to
 * replay verbatim to a retrying client.
 */
@Entity
@Table(name = "idempotency_key", schema = "application")
@IdClass(IdempotencyKeyEntity.Key.class)
class IdempotencyKeyEntity {

    /**
     * Composite key: one key may be used once per operation.
     *
     * <p>A class rather than a record, because JPA requires an {@code @IdClass}
     * to be a public class with a public no-argument constructor.
     */
    public static class Key implements Serializable {

        private String idempotencyKey;
        private String operation;

        public Key() {}

        public Key(String idempotencyKey, String operation) {
            this.idempotencyKey = idempotencyKey;
            this.operation = operation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(idempotencyKey, that.idempotencyKey) && Objects.equals(operation, that.operation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idempotencyKey, operation);
        }
    }

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Id
    @Column(name = "operation", nullable = false, length = 64, updatable = false)
    private String operation;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyEntity() {}

    IdempotencyKeyEntity(
            String idempotencyKey,
            String operation,
            String requestFingerprint,
            UUID applicationId,
            int responseStatus,
            String responseBody,
            Instant createdAt,
            Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestFingerprint = requestFingerprint;
        this.applicationId = applicationId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }

    int getResponseStatus() {
        return responseStatus;
    }

    String getResponseBody() {
        return responseBody;
    }

    /**
     * Redacted: printing the key would put a client-supplied token into the log,
     * and printing the body would defeat the point of keeping responses lean.
     */
    @Override
    public String toString() {
        return "IdempotencyKeyEntity[operation=" + operation + ", status=" + responseStatus + "]";
    }
}
