package com.example.los.application.domain.exception;

import com.example.los.application.domain.model.ApplicationStatus;

/** Raised when the content of an application is changed after it was submitted. */
public class ApplicationNotEditableException extends DomainException {

    public static final String ERROR_CODE = "APPLICATION_NOT_EDITABLE";

    public ApplicationNotEditableException(ApplicationStatus status) {
        super(ERROR_CODE, "Application content cannot be changed while it is " + status);
    }
}
