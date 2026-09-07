package com.example.los.application.adapter.out.persistence;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.usecase.port.DocumentStatusPort;
import com.example.los.events.vocabulary.DocumentType;
import com.example.los.events.vocabulary.ScanOutcome;

/**
 * Answers the "which documents are clean?" question from the local projection.
 *
 * <p>No network call, so submission does not depend on the document service being
 * reachable at that instant.
 */
@Component
class ProjectedDocumentStatusAdapter implements DocumentStatusPort {

    private final DocumentStatusJpaRepository projection;

    ProjectedDocumentStatusAdapter(DocumentStatusJpaRepository projection) {
        this.projection = projection;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<DocumentType> cleanDocumentTypes(ApplicationId applicationId) {
        Set<DocumentType> clean = EnumSet.noneOf(DocumentType.class);

        for (DocumentStatusEntity row : projection.findByApplicationId(applicationId.value())) {
            if (!ScanOutcome.CLEAN.name().equals(row.getStatus())) {
                continue;
            }
            // A document type this service does not recognise is skipped rather
            // than crashing the check. A newer document service may introduce a
            // category before this service is redeployed, and an unknown category
            // can never satisfy a mandatory requirement anyway.
            toDocumentType(row.getDocumentType()).ifPresent(clean::add);
        }
        return clean;
    }

    private static Optional<DocumentType> toDocumentType(String raw) {
        try {
            return Optional.of(DocumentType.valueOf(raw));
        } catch (IllegalArgumentException unknownToThisVersion) {
            return Optional.empty();
        }
    }
}
