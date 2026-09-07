package com.example.los.application.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over the append-only version history. */
interface ApplicationVersionJpaRepository
        extends JpaRepository<ApplicationVersionEntity, ApplicationVersionEntity.Key> {}
