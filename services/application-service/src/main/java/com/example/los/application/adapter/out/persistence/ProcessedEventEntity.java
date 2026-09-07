package com.example.los.application.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Record that a consumer has already handled an event.
 *
 * <p>The composite primary key is the entire mechanism. A consumer inserts this
 * row in the same transaction as its business effect; a duplicate delivery
 * violates the key, the transaction rolls back, and the effect is not applied
 * twice. There is no "check then act" window because the check IS the write.
 */
@Entity
@Table(name = "processed_event", schema = "application")
@IdClass(ProcessedEventEntity.Key.class)
class ProcessedEventEntity {

    /**
     * Composite key. A class rather than a record, because JPA requires an
     * {@code @IdClass} to be a public class with a public no-argument constructor.
     */
    public static class Key implements Serializable {

        private String eventId;
        private String consumer;

        public Key() {}

        public Key(String eventId, String consumer) {
            this.eventId = eventId;
            this.consumer = consumer;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(eventId, that.eventId) && Objects.equals(consumer, that.consumer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumer);
        }
    }

    @Id
    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Id
    @Column(name = "consumer", nullable = false, length = 64, updatable = false)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    ProcessedEventEntity(String eventId, String consumer, Instant processedAt) {
        this.eventId = eventId;
        this.consumer = consumer;
        this.processedAt = processedAt;
    }

    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", consumer=" + consumer + "]";
    }
}
