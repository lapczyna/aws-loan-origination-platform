package com.example.los.workflow.adapter.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA mapping of a workflow instance and its checks.
 *
 * <p>Separate from {@code WorkflowInstance} so the aggregate stays free of
 * persistence annotations and lazy proxies.
 *
 * <p>The checks are mapped eagerly and with cascade, because a workflow is never
 * useful without them: every read of an instance immediately needs to know which
 * checks are outstanding, so lazy loading here would guarantee an N+1 on the
 * scheduler's hot path.
 */
@Entity
@Table(name = "workflow_instance", schema = "workflow")
class WorkflowInstanceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "applicant_reference", nullable = false, length = 32)
    private String applicantReference;

    @Column(name = "status", nullable = false, length = 16)
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

    @Column(name = "recommendation", length = 16)
    private String recommendation;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Earliest due time across this workflow's checks.
     *
     * <p>Denormalised so the scheduler finds due work with a single indexed scan.
     * Recomputed on every save from the check rows, so it cannot drift.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @OneToMany(
            mappedBy = "workflow",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<WorkflowCheckEntity> checks = new ArrayList<>();

    protected WorkflowInstanceEntity() {}

    WorkflowInstanceEntity(UUID id, UUID applicationId) {
        this.id = id;
        this.applicationId = applicationId;
    }

    UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
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

    String getRecommendation() {
        return recommendation;
    }

    void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    String getReasonCode() {
        return reasonCode;
    }

    void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    long getAggregateVersion() {
        return aggregateVersion;
    }

    void setAggregateVersion(long aggregateVersion) {
        this.aggregateVersion = aggregateVersion;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    List<WorkflowCheckEntity> getChecks() {
        return checks;
    }

    void replaceChecks(List<WorkflowCheckEntity> replacements) {
        // Mutated in place rather than reassigned: orphanRemoval tracks this
        // collection instance, and swapping it for a new list makes Hibernate
        // delete every row and re-insert it on each save.
        checks.clear();
        checks.addAll(replacements);
        replacements.forEach(check -> check.setWorkflow(this));
    }

    @Override
    public String toString() {
        return "WorkflowInstanceEntity[id=" + id + ", applicationId=" + applicationId + ", status=" + status + "]";
    }
}
