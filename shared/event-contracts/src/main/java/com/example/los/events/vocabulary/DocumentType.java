package com.example.los.events.vocabulary;

/**
 * Categories of supporting document.
 *
 * <p>{@link #isMandatory()} defines the documents that must reach the CLEAN
 * state before an application may be submitted.
 */
public enum DocumentType {
    PROOF_OF_IDENTITY(true),
    PROOF_OF_INCOME(true),
    PROOF_OF_ADDRESS(false),
    BANK_STATEMENT(false);

    private final boolean mandatory;

    DocumentType(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
