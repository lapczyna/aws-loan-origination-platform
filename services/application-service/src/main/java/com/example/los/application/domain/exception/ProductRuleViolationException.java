package com.example.los.application.domain.exception;

/**
 * Raised when the requested loan falls outside the product's rules.
 *
 * <p>A business failure, never a technical one: it must not be retried.
 */
public class ProductRuleViolationException extends DomainException {

    public ProductRuleViolationException(String errorCode, String message) {
        super(errorCode, message);
    }
}
