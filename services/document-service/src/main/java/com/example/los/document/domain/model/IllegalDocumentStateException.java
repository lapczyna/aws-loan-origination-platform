package com.example.los.document.domain.model;

/** Raised when an operation is attempted on a document in the wrong state. */
public class IllegalDocumentStateException extends RuntimeException {

    public static final String ERROR_CODE = "ILLEGAL_DOCUMENT_STATE";

    private final DocumentStatus current;
    private final DocumentStatus required;

    public IllegalDocumentStateException(DocumentId id, DocumentStatus current, DocumentStatus required) {
        super("Document " + id + " is " + current + " but this operation requires " + required);
        this.current = current;
        this.required = required;
    }

    public String errorCode() {
        return ERROR_CODE;
    }

    public DocumentStatus current() {
        return current;
    }

    public DocumentStatus required() {
        return required;
    }
}
