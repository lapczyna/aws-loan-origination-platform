package com.example.los.document.domain.model;

/** Raised when a document identifier does not resolve to a document. */
public class DocumentNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "DOCUMENT_NOT_FOUND";

    public DocumentNotFoundException(DocumentId id) {
        super("No document with identifier " + id);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
