package com.example.los.application.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over the document-status projection. */
interface DocumentStatusJpaRepository extends JpaRepository<DocumentStatusEntity, UUID> {

    List<DocumentStatusEntity> findByApplicationId(UUID applicationId);
}
