package com.example.los.application.usecase.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.LoanApplication;

/**
 * Outbound port for storing and retrieving the aggregate.
 *
 * <p>Declared in terms of the domain model, not of rows or entities. The
 * persistence adapter is responsible for the mapping, which is what allows the
 * storage technology to change without the use cases noticing.
 */
public interface LoanApplicationRepository {

    Optional<LoanApplication> findById(ApplicationId id);

    /**
     * Persists the aggregate.
     *
     * @throws com.example.los.application.usecase.port.ConcurrentModificationConflict
     *         when another transaction changed the same application first
     */
    LoanApplication save(LoanApplication application);

    /** Applications in the given status, oldest first. Used by the manual-review queue. */
    List<LoanApplication> findByStatus(ApplicationStatus status, int offset, int limit);

    long countByStatus(ApplicationStatus status);

    /**
     * Applications that have been in a non-terminal, in-progress status since
     * before the given instant. Backs the "stuck in processing" alarm.
     */
    List<LoanApplication> findInProgressOlderThan(Instant threshold, int limit);
}
