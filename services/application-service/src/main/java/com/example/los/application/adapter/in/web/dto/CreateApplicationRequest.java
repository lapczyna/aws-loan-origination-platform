package com.example.los.application.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /v1/applications} and {@code PUT /v1/applications/{id}/draft}. */
public record CreateApplicationRequest(
        @NotNull(message = "applicant is required") @Valid ApplicantRequest applicant,
        @NotNull(message = "loan is required") @Valid LoanRequestPayload loan) {

    /** Redacted: contains applicant details. */
    @Override
    public String toString() {
        return "CreateApplicationRequest[redacted]";
    }
}
