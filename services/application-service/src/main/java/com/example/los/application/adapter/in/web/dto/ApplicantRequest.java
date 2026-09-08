package com.example.los.application.adapter.in.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Applicant details supplied by the client.
 *
 * <p>Inbound only. There is no corresponding response type: the API never
 * returns these values back, not even to the client that supplied them, because
 * an endpoint that echoes personal data is an endpoint that leaks it to anyone
 * who obtains a token.
 *
 * <p>A record is safe here. It is short-lived, it is never logged, and the
 * validation messages below are written so that a rejection never quotes the
 * offending value.
 */
@Schema(
        description = """
        **Restricted personal data.** Supplied on creation and never returned by any \
        endpoint. It is stored encrypted, is not written to any log, and only the \
        derived pseudonym crosses a service boundary.
        """)
public record ApplicantRequest(
        @NotBlank(message = "givenName is required")
        @Size(max = 200, message = "givenName is too long")
        String givenName,

        @NotBlank(message = "familyName is required")
        @Size(max = 200, message = "familyName is too long")
        String familyName,

        @NotBlank(message = "emailAddress is required")
        @Email(message = "emailAddress is not a valid address")
        @Size(max = 320, message = "emailAddress is too long")
        String emailAddress,

        @NotNull(message = "dateOfBirth is required")
        @Past(message = "dateOfBirth must be in the past")
        LocalDate dateOfBirth,

        @NotBlank(message = "residenceCountry is required")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "residenceCountry must be an ISO 3166-1 alpha-2 code")
        String residenceCountry) {

    /** Redacted: this type is the one place personal data enters the system. */
    @Override
    public String toString() {
        return "ApplicantRequest[redacted]";
    }
}
