package com.example.los.workflow.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.domain.model.CheckState;
import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowInstance;
import com.example.los.workflow.domain.model.WorkflowStatus;
import com.example.los.workflow.usecase.port.ConcurrentWorkflowUpdate;
import com.example.los.workflow.usecase.port.WorkflowRepository;

/**
 * Persistence adapter for workflow instances.
 *
 * <p>Two things here carry real weight.
 *
 * <p><b>The claim query uses {@code FOR UPDATE SKIP LOCKED}.</b> Without it, two
 * replicas polling for due work would both pick up the same workflow and both
 * call the external provider. Against a credit bureau that bills per lookup,
 * that is a duplicated charge on every assessment; against a rate-limited
 * provider it is wasted quota. With it, replicas take disjoint sets and neither
 * blocks the other.
 *
 * <p><b>{@code next_attempt_at} is recomputed on every save.</b> It is a
 * denormalisation of the check rows, so it is derived rather than maintained by
 * callers — a value the caller had to remember to update is a value that
 * eventually drifts and leaves a workflow that never gets scheduled again.
 */
@Repository
class JpaWorkflowRepository implements WorkflowRepository {

    private final WorkflowInstanceJpaRepository instances;

    JpaWorkflowRepository(WorkflowInstanceJpaRepository instances) {
        this.instances = instances;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowInstance> findById(WorkflowId id) {
        return instances.findById(id.value()).map(JpaWorkflowRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowInstance> findByApplicationId(String applicationId) {
        return instances.findByApplicationId(UUID.fromString(applicationId)).map(JpaWorkflowRepository::toDomain);
    }

    @Override
    @Transactional
    public WorkflowInstance save(WorkflowInstance instance) {
        WorkflowInstanceEntity entity = instances
                .findById(instance.id().value())
                .orElseGet(() -> new WorkflowInstanceEntity(
                        instance.id().value(), UUID.fromString(instance.applicationId())));

        applyTo(entity, instance);

        try {
            instances.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Another replica advanced this workflow first. The caller's work is
            // discarded rather than merged: the winner has already recorded the
            // check outcome, and applying ours on top would double-count an
            // attempt or overwrite a newer state.
            throw new ConcurrentWorkflowUpdate(instance.id());
        }
        return instance;
    }

    @Override
    @Transactional
    public List<WorkflowId> leaseDueWorkflows(Instant now, Duration lease, int batchSize) {
        return instances
                .leaseDue(
                        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(now.plus(lease), ZoneOffset.UTC),
                        batchSize)
                .stream()
                .map(WorkflowId::new)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countRunningStartedBefore(Instant threshold) {
        return instances.countByStatusAndStartedAtBefore(WorkflowStatus.RUNNING.name(), threshold);
    }

    // --- mapping -------------------------------------------------------------

    private static void applyTo(WorkflowInstanceEntity entity, WorkflowInstance instance) {
        entity.setApplicantReference(instance.applicantReference());
        entity.setStatus(instance.status().name());

        AssessmentInputs inputs = instance.inputs();
        entity.setAmountMinorUnits(inputs.amountMinorUnits());
        entity.setCurrency(inputs.currency());
        entity.setTermMonths(inputs.termMonths());
        entity.setPurpose(inputs.purpose());
        entity.setDeclaredAnnualIncomeMinorUnits(inputs.declaredAnnualIncomeMinorUnits());
        entity.setProductCode(inputs.productCode());

        entity.setRecommendation(instance.recommendation().map(Recommendation::name).orElse(null));
        entity.setReasonCode(instance.reasonCode());
        entity.setAggregateVersion(instance.version());
        entity.setStartedAt(instance.startedAt());
        entity.setCompletedAt(instance.completedAt());

        List<WorkflowCheckEntity> checkRows = new ArrayList<>();
        for (CheckState check : instance.checks().values()) {
            checkRows.add(new WorkflowCheckEntity(
                    check.type().name(),
                    check.outcome() == null ? null : check.outcome().name(),
                    check.reasonCode(),
                    check.score(),
                    check.attempts(),
                    check.lastErrorCode(),
                    check.nextAttemptAt(),
                    check.completedAt()));
        }
        entity.replaceChecks(checkRows);

        // Derived, never supplied: the earliest time any outstanding check is due.
        entity.setNextAttemptAt(instance.checks().values().stream()
                .filter(check -> !check.isComplete())
                .map(CheckState::nextAttemptAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null));
    }

    private static WorkflowInstance toDomain(WorkflowInstanceEntity entity) {
        Map<CheckType, CheckState> checks = new EnumMap<>(CheckType.class);
        for (WorkflowCheckEntity row : entity.getChecks()) {
            CheckType type = CheckType.valueOf(row.getCheckType());
            checks.put(
                    type,
                    new CheckState(
                            type,
                            row.getOutcome() == null ? null : CheckOutcome.valueOf(row.getOutcome()),
                            row.getReasonCode(),
                            row.getScore(),
                            row.getAttempts(),
                            row.getLastErrorCode(),
                            row.getNextAttemptAt(),
                            row.getCompletedAt()));
        }

        return WorkflowInstance.reconstitute(
                new WorkflowId(entity.getId()),
                entity.getApplicationId().toString(),
                entity.getApplicantReference(),
                new AssessmentInputs(
                        entity.getAmountMinorUnits(),
                        entity.getCurrency(),
                        entity.getTermMonths(),
                        entity.getPurpose(),
                        entity.getDeclaredAnnualIncomeMinorUnits(),
                        entity.getProductCode()),
                WorkflowStatus.valueOf(entity.getStatus()),
                checks,
                entity.getRecommendation() == null ? null : Recommendation.valueOf(entity.getRecommendation()),
                entity.getReasonCode(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getAggregateVersion());
    }
}
