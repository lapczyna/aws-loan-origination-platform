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
 * A frozen snapshot of the requested terms at one aggregate version.
 *
 * <p>Append-only: rows are inserted and never updated. It carries no personal
 * data, so the history does not multiply the erasure obligation by the number of
 * times an applicant edited their draft.
 */
@Entity
@Table(name = "application_version", schema = "application")
@IdClass(ApplicationVersionEntity.Key.class)
class ApplicationVersionEntity {

    /**
     * Composite primary key: one row per application per version.
     *
     * <p>A class rather than a record. JPA requires an {@code @IdClass} to be a
     * public class with a public no-argument constructor and mutable fields whose
     * names match the entity's identifier properties; a record satisfies none of
     * those, and Hibernate silently maps the entity differently when given one.
     */
    public static class Key implements Serializable {

        private UUID applicationId;
        private long version;

        public Key() {}

        public Key(UUID applicationId, long version) {
            this.applicationId = applicationId;
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return version == that.version && Objects.equals(applicationId, that.applicationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(applicationId, version);
        }
    }

    @Id
    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Id
    @Column(name = "version", nullable = false, updatable = false)
    private long version;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "declared_annual_income_minor_units", nullable = false)
    private long declaredAnnualIncomeMinorUnits;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApplicationVersionEntity() {}

    ApplicationVersionEntity(
            UUID applicationId,
            long version,
            String status,
            long amountMinorUnits,
            String currency,
            int termMonths,
            String purpose,
            long declaredAnnualIncomeMinorUnits,
            String productCode,
            Instant createdAt) {
        this.applicationId = applicationId;
        this.version = version;
        this.status = status;
        this.amountMinorUnits = amountMinorUnits;
        this.currency = currency;
        this.termMonths = termMonths;
        this.purpose = purpose;
        this.declaredAnnualIncomeMinorUnits = declaredAnnualIncomeMinorUnits;
        this.productCode = productCode;
        this.createdAt = createdAt;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    long getVersion() {
        return version;
    }

    String getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
