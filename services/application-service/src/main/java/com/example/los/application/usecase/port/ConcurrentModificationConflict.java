package com.example.los.application.usecase.port;

import com.example.los.application.domain.exception.DomainException;
import com.example.los.application.domain.model.ApplicationId;

/**
 * Raised when an optimistic lock detects that another transaction changed the
 * same application first.
 *
 * <p>Surfaces to the caller as 409 Conflict, not 500: nothing failed, the caller
 * simply acted on a version of the application that is no longer current and
 * should re-read and retry.
 */
public class ConcurrentModificationConflict extends DomainException {

    public static final String ERROR_CODE = "CONCURRENT_MODIFICATION";

    public ConcurrentModificationConflict(ApplicationId id) {
        super(ERROR_CODE, "Application " + id + " was modified concurrently");
    }
}
