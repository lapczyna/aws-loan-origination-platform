package com.example.los.application.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.los.application.domain.exception.ApplicationNotEditableException;
import com.example.los.application.domain.exception.ApplicationNotFoundException;
import com.example.los.application.domain.exception.DomainException;
import com.example.los.application.domain.exception.IllegalStateTransitionException;
import com.example.los.application.domain.exception.MandatoryDocumentsMissingException;
import com.example.los.application.domain.exception.ProductRuleViolationException;
import com.example.los.application.observability.CorrelationId;
import com.example.los.application.usecase.port.ConcurrentModificationConflict;
import com.example.los.application.usecase.port.IdempotencyConflict;
import com.example.los.application.usecase.port.IdempotencyKeyAlreadyUsed;

/**
 * Translates exceptions into RFC 9457 Problem Details.
 *
 * <h2>What a response may contain</h2>
 *
 * <p>A stable {@code errorCode}, a human-readable message written for this
 * purpose, the correlation identifier, and — where it genuinely helps the caller
 * fix their request — the names of the fields that failed validation.
 *
 * <h2>What a response must never contain</h2>
 *
 * <p>Stack traces, exception class names, SQL, internal database identifiers,
 * bean names, file paths, host names, or the value that failed validation. Each
 * of those either helps an attacker map the system or echoes back the very data
 * the platform is supposed to protect. This is why every handler below composes
 * its own message rather than calling {@code exception.getMessage()} on anything
 * originating outside the domain.
 *
 * <p>The unexpected-exception handler is the one that matters most: it is the
 * default path for every defect nobody anticipated, and it deliberately returns
 * a fixed message while logging the detail server-side against the correlation
 * identifier.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Problem type URIs are documentation links, not resolvable endpoints. The
     * host is the RFC 2606 reserved example domain: publishing a real internal
     * documentation host here would leak infrastructure naming.
     */
    private static final String PROBLEM_TYPE_BASE = "https://problems.example.com/loan-origination/";

    private static final String ERROR_CODE = "errorCode";
    private static final String CORRELATION_ID = "correlationId";

    // --- Client errors -------------------------------------------------------

    @ExceptionHandler(ApplicationNotFoundException.class)
    ProblemDetail handleNotFound(ApplicationNotFoundException e, HttpServletRequest request) {
        // The message is composed here rather than taken from the exception,
        // which names the identifier that was looked up.
        return problem(
                HttpStatus.NOT_FOUND,
                ApplicationNotFoundException.ERROR_CODE,
                "No application was found for the supplied identifier.",
                request);
    }

    @ExceptionHandler(ApplicationNotEditableException.class)
    ProblemDetail handleNotEditable(ApplicationNotEditableException e, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "This application can no longer be changed because it has already been submitted.",
                request);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalStateTransitionException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "The requested operation is not valid for this application in its current state.",
                request);
        // The two states are safe to return and are exactly what the caller needs
        // in order to understand the refusal.
        problem.setProperty("currentStatus", e.from().name());
        problem.setProperty("attemptedStatus", e.to().name());
        return problem;
    }

    @ExceptionHandler(MandatoryDocumentsMissingException.class)
    ProblemDetail handleMissingDocuments(MandatoryDocumentsMissingException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                e.errorCode(),
                "This application cannot be submitted until every mandatory document has been uploaded and accepted.",
                request);
        // Document categories, not contents. The caller cannot act without them.
        problem.setProperty(
                "missingDocumentTypes",
                e.missing().stream().map(Enum::name).sorted().toList());
        return problem;
    }

    @ExceptionHandler(ProductRuleViolationException.class)
    ProblemDetail handleProductRule(ProductRuleViolationException e, HttpServletRequest request) {
        // The reason code is the contract; the message is a fixed explanation of
        // that code, never a rendering of the offending values.
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                e.errorCode(),
                "The requested loan does not satisfy the rules of the selected product.",
                request);
    }

    @ExceptionHandler(ConcurrentModificationConflict.class)
    ProblemDetail handleConcurrentModification(ConcurrentModificationConflict e, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "This application was changed by another request. Re-read it and try again.",
                request);
    }

    @ExceptionHandler(IdempotencyConflict.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflict e, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "This idempotency key was already used with a different request body. "
                        + "Use a new key for a different request.",
                request);
    }

    @ExceptionHandler(IdempotencyKeyAlreadyUsed.class)
    ProblemDetail handleIdempotencyRace(IdempotencyKeyAlreadyUsed e, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "An identical request using this idempotency key is already being processed. Retry shortly.",
                request);
    }

    // --- Request validation --------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_VALIDATION_FAILED",
                "The request body failed validation.",
                request);

        // Field name and the constraint message written in the DTO. The rejected
        // value is deliberately NOT included: for this API it is personal data.
        List<Map<String, String>> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        // Jackson's message names the offending field, its type and often its
        // value, so it is never forwarded.
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST_BODY",
                "The request body could not be parsed as valid JSON matching this endpoint's schema.",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PATH_PARAMETER",
                "A path or query parameter was not in the expected format.",
                request);
        problem.setProperty("parameter", e.getName());
        return problem;
    }

    /**
     * A value that passed bean validation but that the domain still refuses.
     *
     * <p>An unrecognised enum constant, an identifier that is not a UUID, a
     * currency code that no longer exists — all reach the domain as an
     * {@link IllegalArgumentException}. Without this handler they would fall
     * through to the 500 path and be reported as a server fault, when in fact the
     * request was malformed.
     *
     * <p>The exception's own message is deliberately discarded: it typically
     * quotes the rejected value, which for this API can be personal data.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidValue(IllegalArgumentException e, HttpServletRequest request) {
        log.debug("Rejected an unacceptable request value. type={}", e.getClass().getSimpleName());
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_VALUE",
                "The request contained a value that is not accepted by this endpoint.",
                request);
    }

    // --- Authorisation -------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        // No detail about what was missing: telling a caller which scope they
        // lack maps the authorisation model for them.
        return problem(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "The presented credentials do not permit this operation.",
                request);
    }

    // --- Anything unanticipated ---------------------------------------------

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleUnmappedDomainException(DomainException e, HttpServletRequest request) {
        // A domain exception without a specific handler still has a safe code.
        log.warn(
                "Unmapped domain exception. errorCode={} correlationId={} type={}",
                e.errorCode(),
                CorrelationId.current(),
                e.getClass().getSimpleName());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.errorCode(), "The request could not be completed.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String correlationId = CorrelationId.current();

        // The detail stays server-side. The caller gets a correlation identifier
        // and nothing else, which is enough for support to find this log entry
        // and not enough for anyone to learn about the internals.
        log.error("Unhandled exception. correlationId={} type={}", correlationId, e.getClass().getName(), e);

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed. Quote the correlation identifier when contacting support.",
                request);
    }

    private static ProblemDetail problem(HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + errorCode.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty(ERROR_CODE, errorCode);
        problem.setProperty(CORRELATION_ID, CorrelationId.current());
        // The request path is the caller's own input, so echoing it leaks nothing.
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
