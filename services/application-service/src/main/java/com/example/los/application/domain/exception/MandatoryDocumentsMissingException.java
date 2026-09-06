package com.example.los.application.domain.exception;

import java.util.Set;

import com.example.los.events.vocabulary.DocumentType;

/**
 * Raised on submission when a mandatory document has not reached the CLEAN state.
 *
 * <p>The missing document types are safe to return to the caller: they are
 * categories, not content, and the caller needs them to know what to upload.
 */
public class MandatoryDocumentsMissingException extends DomainException {

    public static final String ERROR_CODE = "MANDATORY_DOCUMENTS_MISSING";

    private final Set<DocumentType> missing;

    public MandatoryDocumentsMissingException(Set<DocumentType> missing) {
        super(ERROR_CODE, "Application cannot be submitted: " + missing.size() + " mandatory document(s) missing");
        this.missing = Set.copyOf(missing);
    }

    public Set<DocumentType> missing() {
        return missing;
    }
}
