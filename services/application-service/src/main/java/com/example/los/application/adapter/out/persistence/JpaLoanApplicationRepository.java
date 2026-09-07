package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.usecase.port.ConcurrentModificationConflict;
import com.example.los.application.usecase.port.LoanApplicationRepository;

/**
 * Persistence adapter for the loan application aggregate.
 *
 * <p>Two behaviours are worth calling out.
 *
 * <p><b>Optimistic locking.</b> Concurrent writes to one application are
 * resolved by the {@code row_version} column, not by a pessimistic lock. Two
 * simultaneous submissions therefore cannot both succeed: the second commit
 * fails and is translated into a 409 rather than silently overwriting the first.
 * Translating the framework exception here, at the boundary, keeps
 * {@code OptimisticLockingFailureException} out of the use cases.
 *
 * <p><b>Version history.</b> Every save also appends the requested terms to the
 * append-only history, in the same transaction, so the history can never
 * disagree with the aggregate.
 */
@Repository
class JpaLoanApplicationRepository implements LoanApplicationRepository {

    private final LoanApplicationJpaRepository applications;
    private final ApplicationVersionJpaRepository versions;
    private final LoanApplicationMapper mapper;

    JpaLoanApplicationRepository(
            LoanApplicationJpaRepository applications,
            ApplicationVersionJpaRepository versions,
            LoanApplicationMapper mapper) {
        this.applications = applications;
        this.versions = versions;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoanApplication> findById(ApplicationId id) {
        return applications.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public LoanApplication save(LoanApplication application) {
        LoanApplicationEntity existing =
                applications.findById(application.id().value()).orElse(null);
        LoanApplicationEntity entity = mapper.toEntity(application, existing);

        try {
            applications.saveAndFlush(entity);
            // Appended in the same transaction: an aggregate at version N always
            // has a history row for version N, or neither exists.
            versions.save(mapper.toVersionEntity(application));
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentModificationConflict(application.id());
        } catch (DataIntegrityViolationException e) {
            // A duplicate history row means another transaction already wrote
            // this aggregate version, which is the same race the optimistic lock
            // exists to catch.
            throw new ConcurrentModificationConflict(application.id());
        }

        return application;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanApplication> findByStatus(ApplicationStatus status, int offset, int limit) {
        return applications
                .findByStatusOrderByUpdatedAtAsc(status.name(), PageRequest.of(offset / limit, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(ApplicationStatus status) {
        return applications.countByStatus(status.name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanApplication> findInProgressOlderThan(Instant threshold, int limit) {
        return applications.findInProgressOlderThan(threshold, PageRequest.of(0, limit)).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
