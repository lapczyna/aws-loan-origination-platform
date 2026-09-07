package com.example.los.workflow.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** JPA mapping of one check's durable state within a workflow. */
@Entity
@Table(name = "workflow_check", schema = "workflow")
@IdClass(WorkflowCheckEntity.Key.class)
class WorkflowCheckEntity {

    /**
     * Composite key. A class, not a record: JPA requires an {@code @IdClass} to
     * be a public class with a public no-argument constructor.
     */
    public static class Key implements Serializable {

        private UUID workflow;
        private String checkType;

        public Key() {}

        public Key(UUID workflow, String checkType) {
            this.workflow = workflow;
            this.checkType = checkType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(workflow, that.workflow) && Objects.equals(checkType, that.checkType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workflow, checkType);
        }
    }

    @Id
    @ManyToOne(optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private WorkflowInstanceEntity workflow;

    @Id
    @Column(name = "check_type", nullable = false, length = 16)
    private String checkType;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "score")
    private Integer score;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowCheckEntity() {}

    WorkflowCheckEntity(
            String checkType,
            String outcome,
            String reasonCode,
            Integer score,
            int attempts,
            String lastErrorCode,
            Instant nextAttemptAt,
            Instant completedAt) {
        this.checkType = checkType;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.score = score;
        this.attempts = attempts;
        this.lastErrorCode = lastErrorCode;
        this.nextAttemptAt = nextAttemptAt;
        this.completedAt = completedAt;
    }

    void setWorkflow(WorkflowInstanceEntity workflow) {
        this.workflow = workflow;
    }

    String getCheckType() {
        return checkType;
    }

    String getOutcome() {
        return outcome;
    }

    String getReasonCode() {
        return reasonCode;
    }

    Integer getScore() {
        return score;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastErrorCode() {
        return lastErrorCode;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public String toString() {
        return "WorkflowCheckEntity[checkType=" + checkType + ", outcome=" + outcome + ", attempts=" + attempts + "]";
    }
}
