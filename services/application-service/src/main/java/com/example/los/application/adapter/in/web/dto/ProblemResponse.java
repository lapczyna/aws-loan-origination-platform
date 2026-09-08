package com.example.los.application.adapter.in.web.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The shape of every error response, for the generated OpenAPI document.
 *
 * <p><b>This type is never instantiated.</b> Errors are returned as Spring's
 * {@code ProblemDetail}, which is a generic map-backed type and therefore
 * generates a useless schema: an object with a few RFC 9457 fields and no
 * indication of what {@code errorCode} can contain. Declaring the shape here
 * means the published contract actually tells a client what to branch on.
 *
 * <p>It is a documentation type, and the risk of one is that it drifts from what
 * is really returned. That risk is bounded here: the error codes below are
 * asserted against real responses by the integration tests, so a code that stops
 * being produced, or one that appears without being listed, is caught by a test
 * rather than by a reader.
 *
 * @param type an RFC 9457 type URI; {@code about:blank} unless a more specific one applies
 * @param title a short, human-readable summary
 * @param status the HTTP status code
 * @param detail prose for a human; may be reworded, so never branch on it
 * @param instance the request path
 * @param errorCode the stable, machine-readable code to branch on
 * @param correlationId the identifier tying this response to the platform's logs
 * @param timestamp when the error occurred
 */
@Schema(
        name = "Problem",
        description =
                """
                An RFC 9457 Problem Detail.

                It never contains a stack trace, a SQL fragment, a table name, an internal \
                identifier, or an exception message. Validation errors name the **field** and \
                never echo its value, because the value is frequently the personal data the \
                platform exists to protect.

                Branch on `errorCode`, never on `detail`.
                """)
public record ProblemResponse(
        @Schema(example = "about:blank") String type,
        @Schema(example = "Conflict") String title,
        @Schema(example = "409") Integer status,
        @Schema(description = "Prose for a human. May be reworded between releases.") String detail,
        @Schema(example = "/v1/applications/018f3a9c-6b21-7c4e-9d55-2a1b8e7f0c33/submit") String instance,
        @Schema(
                        description = "Stable and machine-readable. This is what a client branches on.",
                        allowableValues = {
                            "REQUEST_VALIDATION_FAILED",
                            "MALFORMED_REQUEST_BODY",
                            "INVALID_REQUEST_VALUE",
                            "INVALID_PATH_PARAMETER",
                            "ACCESS_DENIED",
                            "APPLICATION_NOT_FOUND",
                            "APPLICATION_NOT_EDITABLE",
                            "DOCUMENT_NOT_FOUND",
                            "ILLEGAL_STATE_TRANSITION",
                            "ILLEGAL_DOCUMENT_STATE",
                            "MANDATORY_DOCUMENTS_MISSING",
                            "IDEMPOTENCY_KEY_REUSED",
                            "IDEMPOTENCY_KEY_IN_USE",
                            "CONCURRENT_MODIFICATION",
                            "CONCURRENT_DOCUMENT_UPDATE",
                            "OBJECT_STORAGE_UNAVAILABLE",
                            "SCANNER_UNAVAILABLE",
                            "INTERNAL_ERROR"
                        })
                String errorCode,
        @Schema(
                        description = "Ties this response to the platform's logs. Quote it when reporting a problem: "
                                + "it is deliberately the only link, because the body carries nothing else.")
                String correlationId,
        Instant timestamp) {}
