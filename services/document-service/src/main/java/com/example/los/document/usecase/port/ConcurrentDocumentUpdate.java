package com.example.los.document.usecase.port;

import com.example.los.document.domain.model.DocumentId;

/** Raised when another replica changed the same document first. */
public class ConcurrentDocumentUpdate extends RuntimeException {

    public static final String ERROR_CODE = "CONCURRENT_DOCUMENT_UPDATE";

    public ConcurrentDocumentUpdate(DocumentId id) {
        super("Document " + id + " was modified concurrently");
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
