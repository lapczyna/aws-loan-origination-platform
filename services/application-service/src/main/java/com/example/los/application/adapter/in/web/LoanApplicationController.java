package com.example.los.application.adapter.in.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;

import jakarta.validation.Valid;

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
    ResponseEntity<Object> createDraft(
            @RequestHeader(value = IdempotentRequestExecutor.HEADER, required = false) String idempotencyKey,
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
    ResponseEntity<Object> submit(
            @PathVariable String applicationId,
            @RequestHeader(value = IdempotentRequestExecutor.HEADER, required = false) String idempotencyKey) {

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
