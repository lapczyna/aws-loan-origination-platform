package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over the aggregate's table. Package-private by design. */
interface LoanApplicationJpaRepository extends JpaRepository<LoanApplicationEntity, UUID> {

    List<LoanApplicationEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    long countByStatus(String status);

    @Query(
            """
            select e from LoanApplicationEntity e
            where e.status in ('SUBMITTED', 'VALIDATING', 'CHECKS_IN_PROGRESS')
              and e.updatedAt < :threshold
            order by e.updatedAt asc
            """)
    List<LoanApplicationEntity> findInProgressOlderThan(
            @Param("threshold") Instant threshold, Pageable pageable);
}
