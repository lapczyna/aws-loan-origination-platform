package com.example.los.application.domain.exception;

import com.example.los.application.domain.model.ApplicationId;

/** Raised when an application identifier does not resolve to an application. */
public class ApplicationNotFoundException extends DomainException {

    public static final String ERROR_CODE = "APPLICATION_NOT_FOUND";

    public ApplicationNotFoundException(ApplicationId id) {
        super(ERROR_CODE, "No application with identifier " + id);
    }
}
