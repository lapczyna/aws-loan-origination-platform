package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA mapping of the loan application aggregate.
 *
 * <p>Deliberately a separate type from {@code LoanApplication}. The aggregate
 * expresses behaviour and invariants; this expresses a row. Merging them would
 * force JPA's requirements — a no-argument constructor, mutable fields, lazy
 * proxies — onto the model that is supposed to be the platform's clearest code,
 * and it would put a persistence annotation on every business concept.
 *
 * <p>This type never leaves the persistence adapter. It is not returned from a
 * repository, not exposed by the API and not serialised onto an event.
 *
 * <p>It is not a record and carries no Lombok {@code @Data}: it holds personal
 * data, and a generated {@code toString()} would print it.
 */
@Entity
@Table(name = "loan_application", schema = "application")
class LoanApplicationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "applicant_reference", nullable = false, length = 32)
    private String applicantReference;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    // --- Personal data. Never selected into an event, a log or an audit record.
    @Column(name = "applicant_given_name", nullable = false, length = 200)
    private String applicantGivenName;

    @Column(name = "applicant_family_name", nullable = false, length = 200)
    private String applicantFamilyName;

    @Column(name = "applicant_email", nullable = false, length = 320)
    private String applicantEmail;

    @Column(name = "applicant_date_of_birth", nullable = false)
    private LocalDate applicantDateOfBirth;

    @Column(name = "applicant_residence_country", nullable = false, length = 2)
    private String applicantResidenceCountry;

    // --- The request.
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

    // --- The decision, once one exists.
    @Column(name = "decision_type", length = 16)
    private String decisionType;

    @Column(name = "decision_reason_code", length = 64)
    private String decisionReasonCode;

    @Column(name = "decision_decided_by", length = 64)
    private String decisionDecidedBy;

    /** Business version: one increment per business operation. Not the lock. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /**
     * Optimistic lock version, managed by JPA and counting physical writes.
     *
     * <p>This is what makes two concurrent submissions of the same application
     * safe: the second commit fails rather than overwriting the first.
     */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application code; use the mapper. */
    protected LoanApplicationEntity() {}

    LoanApplicationEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getApplicantReference() {
        return applicantReference;
    }

    void setApplicantReference(String applicantReference) {
        this.applicantReference = applicantReference;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getApplicantGivenName() {
        return applicantGivenName;
    }

    void setApplicantGivenName(String applicantGivenName) {
        this.applicantGivenName = applicantGivenName;
    }

    String getApplicantFamilyName() {
        return applicantFamilyName;
    }

    void setApplicantFamilyName(String applicantFamilyName) {
        this.applicantFamilyName = applicantFamilyName;
    }

    String getApplicantEmail() {
        return applicantEmail;
    }

    void setApplicantEmail(String applicantEmail) {
        this.applicantEmail = applicantEmail;
    }

    LocalDate getApplicantDateOfBirth() {
        return applicantDateOfBirth;
    }

    void setApplicantDateOfBirth(LocalDate applicantDateOfBirth) {
        this.applicantDateOfBirth = applicantDateOfBirth;
    }

    String getApplicantResidenceCountry() {
        return applicantResidenceCountry;
    }

    void setApplicantResidenceCountry(String applicantResidenceCountry) {
        this.applicantResidenceCountry = applicantResidenceCountry;
    }

    long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    void setAmountMinorUnits(long amountMinorUnits) {
        this.amountMinorUnits = amountMinorUnits;
    }

    String getCurrency() {
        return currency;
    }

    void setCurrency(String currency) {
        this.currency = currency;
    }

    int getTermMonths() {
        return termMonths;
    }

    void setTermMonths(int termMonths) {
        this.termMonths = termMonths;
    }

    String getPurpose() {
        return purpose;
    }

    void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    long getDeclaredAnnualIncomeMinorUnits() {
        return declaredAnnualIncomeMinorUnits;
    }

    void setDeclaredAnnualIncomeMinorUnits(long declaredAnnualIncomeMinorUnits) {
        this.declaredAnnualIncomeMinorUnits = declaredAnnualIncomeMinorUnits;
    }

    String getProductCode() {
        return productCode;
    }

    void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    String getDecisionType() {
        return decisionType;
    }

    void setDecisionType(String decisionType) {
        this.decisionType = decisionType;
    }

    String getDecisionReasonCode() {
        return decisionReasonCode;
    }

    void setDecisionReasonCode(String decisionReasonCode) {
        this.decisionReasonCode = decisionReasonCode;
    }

    String getDecisionDecidedBy() {
        return decisionDecidedBy;
    }

    void setDecisionDecidedBy(String decisionDecidedBy) {
        this.decisionDecidedBy = decisionDecidedBy;
    }

    long getAggregateVersion() {
        return aggregateVersion;
    }

    void setAggregateVersion(long aggregateVersion) {
        this.aggregateVersion = aggregateVersion;
    }

    long getRowVersion() {
        return rowVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Redacted: this entity holds personal data. */
    @Override
    public String toString() {
        return "LoanApplicationEntity[id=" + id + ", status=" + status + ", version=" + aggregateVersion + "]";
    }
}
