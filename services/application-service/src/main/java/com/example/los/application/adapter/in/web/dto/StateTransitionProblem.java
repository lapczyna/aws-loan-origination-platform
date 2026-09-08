package com.example.los.application.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The 409 returned when an operation is not legal for an application's current
 * state.
 *
 * <p>A documentation type, like {@link ProblemResponse}. The two states below
 * are safe to return and are exactly what the caller needs in order to
 * understand the refusal — a client that reads them can tell "already submitted"
 * from "already decided" without guessing.
 */
@Schema(
        name = "StateTransitionProblem",
        description = "Returned when the requested operation is not valid for the application's current state.")
public record StateTransitionProblem(
        @Schema(example = "ILLEGAL_STATE_TRANSITION") String errorCode,
        @Schema(description = "The state the application is actually in.") String currentStatus,
        @Schema(description = "The state the request would have moved it to.") String attemptedStatus) {}
