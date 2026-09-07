package com.example.los.audit.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over the audit trail. Reads and inserts only. */
interface AuditRecordJpaRepository extends JpaRepository<AuditRecordEntity, AuditRecordEntity.Key> {

    /** The tail of a chain, which the next record hashes onto. */
    Optional<AuditRecordEntity> findTopByChainKeyOrderBySequenceNumberDesc(String chainKey);

    List<AuditRecordEntity> findByChainKeyOrderBySequenceNumberAsc(String chainKey);
}
