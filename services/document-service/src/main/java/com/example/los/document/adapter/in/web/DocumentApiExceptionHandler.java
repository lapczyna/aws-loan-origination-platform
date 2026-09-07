package com.example.los.document.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
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

import com.example.los.document.adapter.out.persistence.ConcurrentDocumentUpdate;
import com.example.los.document.adapter.out.s3.ObjectStorageUnavailableException;
import com.example.los.document.domain.model.DocumentNotFoundException;
import com.example.los.document.domain.model.IllegalDocumentStateException;
import com.example.los.document.domain.model.UploadVerificationFailedException;
import com.example.los.document.observability.CorrelationId;

/**
 * Translates exceptions into RFC 9457 Problem Details.
 *
 * <p>Same discipline as the application service: a stable error code, a message
 * written for the purpose, and the correlation identifier — never a stack trace,
 * an AWS SDK message, a bucket name, an object key or a presigned URL.
 *
 * <p>The storage-failure handler matters most here. An AWS SDK exception message
 * routinely quotes the request that failed, which in this service means the
 * bucket, the key, and in the presigning path the signed URL itself. Forwarding
 * that message to a caller would hand them the credential the whole design exists
 * to protect.
 */
@RestControllerAdvice
class DocumentApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentApiExceptionHandler.class);

    private static final String PROBLEM_TYPE_BASE = "https://problems.example.com/loan-origination/";
    private static final String ERROR_CODE = "errorCode";
    private static final String CORRELATION_ID = "correlationId";

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleNotFound(DocumentNotFoundException e, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND,
                DocumentNotFoundException.ERROR_CODE,
                "No document was found for the supplied identifier.",
                request);
    }

    @ExceptionHandler(UploadVerificationFailedException.class)
    ProblemDetail handleVerificationFailed(UploadVerificationFailedException e, HttpServletRequest request) {
        // The caller needs to know which constraint their upload violated in
        // order to fix it, and the code says exactly that without quoting the
        // object, the key or any value from the file.
        log.warn("Upload verification failed. errorCode={} correlationId={}", e.errorCode(), CorrelationId.current());
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                e.errorCode(),
                "The uploaded document did not match what was declared when the upload was requested, "
                        + "and has been refused.",
                request);
    }

    @ExceptionHandler(IllegalDocumentStateException.class)
    ProblemDetail handleIllegalState(IllegalDocumentStateException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "This operation is not valid for the document in its current state.",
                request);
        // Both statuses are safe and are what the caller needs to understand the
        // refusal — for example that the document has already been scanned.
        problem.setProperty("currentStatus", e.current().name());
        problem.setProperty("requiredStatus", e.required().name());
        return problem;
    }

    @ExceptionHandler(ConcurrentDocumentUpdate.class)
    ProblemDetail handleConcurrentUpdate(ConcurrentDocumentUpdate e, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                e.errorCode(),
                "This document was changed by another request. Re-read it and try again.",
                request);
    }

    @ExceptionHandler(ObjectStorageUnavailableException.class)
    ProblemDetail handleStorageUnavailable(ObjectStorageUnavailableException e, HttpServletRequest request) {
        // Logged with the exception so an operator has the detail; the response
        // gets a fixed message. The SDK's own message can contain the bucket, the
        // key and the signed URL.
        log.error("Object storage was unavailable. correlationId={}", CorrelationId.current(), e);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                e.errorCode(),
                "Document storage is temporarily unavailable. Retry shortly.",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "The request body failed validation.", request);

        List<Map<String, String>> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST_BODY",
                "The request body could not be parsed as valid JSON matching this endpoint's schema.",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidValue(IllegalArgumentException e, HttpServletRequest request) {
        // The exception message typically quotes the rejected value, so it is
        // discarded rather than forwarded.
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_VALUE",
                "The request contained a value that is not accepted by this endpoint.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return problem(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "The presented credentials do not permit this operation.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String correlationId = CorrelationId.current();
        log.error("Unhandled exception. correlationId={} type={}", correlationId, e.getClass().getName(), e);

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed. Quote the correlation identifier when contacting support.",
                request);
    }

    private static ProblemDetail problem(
            HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + errorCode.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty(ERROR_CODE, errorCode);
        problem.setProperty(CORRELATION_ID, CorrelationId.current());
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
