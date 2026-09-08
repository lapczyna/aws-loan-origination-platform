package com.example.los.application.adapter.in.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.los.application.adapter.in.web.dto.ApplicantRequest;
import com.example.los.application.adapter.in.web.dto.ApplicationResponse;
import com.example.los.application.adapter.in.web.dto.ApplicationStatusResponse;
import com.example.los.application.adapter.in.web.dto.CreateApplicationRequest;
import com.example.los.application.adapter.in.web.dto.LoanRequestPayload;
import com.example.los.application.adapter.in.web.dto.MissingDocumentsProblem;
import com.example.los.application.adapter.in.web.dto.ProblemResponse;
import com.example.los.application.adapter.in.web.dto.StateTransitionProblem;
import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.LoanPurpose;
import com.example.los.application.domain.model.LoanRequest;
import com.example.los.application.domain.model.LoanTerm;
import com.example.los.application.domain.model.Money;
import com.example.los.application.observability.CorrelationId;
import com.example.los.application.usecase.service.ApplicationQueryUseCase;
import com.example.los.application.usecase.service.ManageApplicationDraftUseCase;
import com.example.los.application.usecase.service.SubmitApplicationUseCase;

/**
 * The loan application API.
 *
 * <p>Every response type is an explicit DTO. No JPA entity and no domain object
 * is ever returned: exposing an entity couples the wire format to the schema, and
 * exposing the aggregate would return the applicant's personal data to anyone
 * holding a token.
 *
 * <p>Authorisation is declared per endpoint with scopes rather than applied
 * globally, so reading an application and deciding one are separately grantable
 * and a partner client can be issued exactly the scopes it needs.
 */
@RestController
@RequestMapping("/v1/applications")
@Tag(name = "Applications")
class LoanApplicationController {

    private static final String OPERATION_CREATE = "create-application";
    private static final String OPERATION_SUBMIT = "submit-application";

    private final ManageApplicationDraftUseCase drafts;
    private final SubmitApplicationUseCase submissions;
    private final ApplicationQueryUseCase queries;
    private final IdempotentRequestExecutor idempotent;
    private final Clock clock;

    LoanApplicationController(
            ManageApplicationDraftUseCase drafts,
            SubmitApplicationUseCase submissions,
            ApplicationQueryUseCase queries,
            IdempotentRequestExecutor idempotent,
            Clock clock) {
        this.drafts = drafts;
        this.submissions = submissions;
        this.queries = queries;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    /**
     * Creates a draft application.
     *
     * <p>Idempotent: repeating the request with the same {@code Idempotency-Key}
     * and body returns the original application instead of creating a second one.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_applications:write')")
    @Operation(
            summary = "Create a draft application",
            description = """
                    Creates the application in `DRAFT`. Nothing is assessed until it is \
                    submitted, and it cannot be submitted until every mandatory document \
                    has been uploaded and accepted.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Created, or replayed. A replay carries `Idempotent-Replay: true` "
                    + "and is the original response, not a new application.",
            content = @Content(schema = @Schema(implementation = ApplicationResponse.class)))
    @ApiResponse(responseCode = "400", description = "The request failed validation.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "No token, or a token that is expired or "
            + "minted for another audience.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "403", description = "The token lacks `applications:write`.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The `Idempotency-Key` was already used for a different request, "
                    + "or the original is still in flight.",
            content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    ResponseEntity<Object> createDraft(
            @Parameter(
                            description = "A client-generated key, unique per logical operation. "
                                    + "Reuse it when retrying the SAME request; never reuse it for a different one.",
                            example = "3f8c1b6e-9a2d-4c7f-8f10-2b5d9a1c4e77")
                    @RequestHeader(value = IdempotentRequestExecutor.HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateApplicationRequest request) {

        String correlationId = CorrelationId.current();

        return idempotent.execute(idempotencyKey, OPERATION_CREATE, request, HttpStatus.CREATED, () -> {
            LoanApplication application = drafts.createDraft(
                    toApplicantDetails(request.applicant()), toLoanRequest(request.loan()), correlationId);
            return new IdempotentRequestExecutor.IdempotentResult<>(
                    application.id().toString(), ApplicationResponse.from(application));
        });
    }

    /** Replaces the content of a draft. Refused once the application is submitted. */
    @PutMapping("/{applicationId}/draft")
    @PreAuthorize("hasAuthority('SCOPE_applications:write')")
    @Operation(
            summary = "Replace the contents of a draft",
            description = """
                    Refused once the application has been submitted. Changing the amount \
                    after submission would mean the assessment ran against figures nobody \
                    submitted.
                    """)
    @ApiResponse(responseCode = "200", description = "The updated draft.")
    @ApiResponse(
            responseCode = "409",
            description = "The application is no longer a draft. The body names the current "
                    + "and attempted states.",
            content = @Content(schema = @Schema(implementation = StateTransitionProblem.class)))
    ApplicationResponse updateDraft(
            @PathVariable String applicationId, @Valid @RequestBody CreateApplicationRequest request) {

        LoanApplication application = drafts.updateDraft(
                ApplicationId.of(applicationId),
                toApplicantDetails(request.applicant()),
                toLoanRequest(request.loan()),
                CorrelationId.current());

        return ApplicationResponse.from(application);
    }

    /**
     * Submits the application for assessment.
     *
     * <p>Idempotent, and it is the endpoint that most needs to be: a client that
     * times out here and retries must not submit twice.
     *
     * <p>Returns 202 Accepted rather than 200. Submission starts an assessment
     * that completes asynchronously; the response says the request was accepted,
     * not that a decision was reached.
     */
    @PostMapping("/{applicationId}/submit")
    @PreAuthorize("hasAuthority('SCOPE_applications:write')")
    @Operation(
            summary = "Submit an application for assessment",
            description = """
                    Starts the assessment. Returns **202 Accepted**, not 200: the response \
                    says the request was accepted, not that a decision was reached. Poll \
                    `/status` for the outcome.

                    There is no request body. The path identifier is what the idempotency \
                    fingerprint is taken over, so reusing a key against a different \
                    application is a conflict rather than a silent replay of the first \
                    application's result — which would tell a client its second application \
                    had been submitted when it had not.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Accepted for assessment, or replayed.",
            content = @Content(schema = @Schema(implementation = ApplicationResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such application.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency key conflict.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "A mandatory document has not been uploaded and accepted. The body "
                    + "names the missing CATEGORIES, never a document's contents.",
            content = @Content(schema = @Schema(implementation = MissingDocumentsProblem.class)))
    ResponseEntity<Object> submit(
            @PathVariable String applicationId,
            @Parameter(description = "See the create operation.")
                    @RequestHeader(value = IdempotentRequestExecutor.HEADER, required = false)
                    String idempotencyKey) {

        ApplicationId id = ApplicationId.of(applicationId);
        String correlationId = CorrelationId.current();

        // The path identifier is the request content for fingerprinting: a submit
        // has no body, so reusing a key against a different application is the
        // only way the same key can mean two different things.
        return idempotent.execute(idempotencyKey, OPERATION_SUBMIT, applicationId, HttpStatus.ACCEPTED, () -> {
            LoanApplication application = submissions.submit(id, correlationId);
            return new IdempotentRequestExecutor.IdempotentResult<>(
                    applicationId, ApplicationResponse.from(application));
        });
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('SCOPE_applications:read')")
    @Operation(
            summary = "Retrieve an application",
            description = """
                    The full representation. It identifies the applicant by pseudonym and \
                    initials, never by the values that were supplied.
                    """)
    @ApiResponse(responseCode = "200", description = "The application.")
    @ApiResponse(
            responseCode = "404",
            description = "No such application. Deliberately indistinguishable from "
                    + "\"exists but is not yours\": telling the two apart lets a caller "
                    + "enumerate which identifiers exist.",
            content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    ApplicationResponse getApplication(@PathVariable String applicationId) {
        return ApplicationResponse.from(queries.getById(ApplicationId.of(applicationId)));
    }

    /**
     * The current status.
     *
     * <p>A separate, deliberately small endpoint: status polling is the
     * highest-volume read on the platform, and a client waiting for a decision
     * should not transfer the whole application on every poll.
     */
    @GetMapping("/{applicationId}/status")
    @PreAuthorize("hasAuthority('SCOPE_applications:read')")
    @Operation(
            summary = "The current status",
            description = """
                    Deliberately small and separate from the full representation. Status \
                    polling is the highest-volume read on the platform, and a client waiting \
                    for a decision should not transfer the whole application on every poll.

                    Stop polling when `terminal` is true.
                    """)
    @ApiResponse(responseCode = "200", description = "The current status.")
    @ApiResponse(responseCode = "404", description = "No such application.", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    ApplicationStatusResponse getStatus(@PathVariable String applicationId) {
        return ApplicationStatusResponse.from(queries.getById(ApplicationId.of(applicationId)));
    }

    // --- Mapping from wire format to domain ----------------------------------

    private ApplicantDetails toApplicantDetails(ApplicantRequest request) {
        return ApplicantDetails.of(
                request.givenName(),
                request.familyName(),
                request.emailAddress(),
                request.dateOfBirth(),
                request.residenceCountry(),
                LocalDate.ofInstant(clock.instant(), clock.getZone()));
    }

    private LoanRequest toLoanRequest(LoanRequestPayload payload) {
        Currency currency = Currency.getInstance(payload.currency());
        return new LoanRequest(
                new Money(payload.amountMinorUnits(), currency),
                LoanTerm.ofMonths(payload.termMonths()),
                LoanPurpose.valueOf(payload.purpose()),
                new Money(payload.declaredAnnualIncomeMinorUnits(), currency),
                payload.productCode());
    }
}
