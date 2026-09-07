package com.example.los.workflow.usecase.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowInstance;

/** Outbound port for storing and retrieving workflow instances. */
public interface WorkflowRepository {

    Optional<WorkflowInstance> findById(WorkflowId id);

    Optional<WorkflowInstance> findByApplicationId(String applicationId);

    WorkflowInstance save(WorkflowInstance instance);

    /**
     * Leases due workflows to this replica and returns their identifiers.
     *
     * <p>A lease rather than a plain query, because the two halves of the work
     * cannot share one transaction. Running every external check for a whole
     * batch inside the claiming transaction would hold row locks for as long as
     * the slowest provider takes, and one workflow's failure would roll back the
     * checks that already succeeded for all the others.
     *
     * <p>So the claim commits immediately, pushing each claimed workflow's next
     * attempt time forward by {@code lease}. Another replica polling in the
     * meantime does not see them. If this replica dies mid-batch the lease simply
     * expires and the workflow is picked up again — at worst a delayed
     * assessment, never a lost one.
     *
     * <p>Implementations must combine the select and the update atomically, with
     * {@code FOR UPDATE SKIP LOCKED}, so two replicas cannot lease the same
     * workflow and call the same provider twice.
     *
     * @param now       the current instant
     * @param lease     how long the claimed workflows are hidden from other pollers
     * @param batchSize maximum number of workflows to lease
     * @return identifiers of the leased workflows
     */
    List<WorkflowId> leaseDueWorkflows(Instant now, Duration lease, int batchSize);

    /** Running workflows that started before the given instant. Backs the stuck-workflow alarm. */
    long countRunningStartedBefore(Instant threshold);
}
