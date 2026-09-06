package com.example.los.application.usecase.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.exception.ApplicationNotFoundException;
import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.LoanRequest;
import com.example.los.application.domain.model.ProductRules;
import com.example.los.application.usecase.port.ApplicantPseudonymiser;
import com.example.los.application.usecase.port.LoanApplicationRepository;
import com.example.los.application.usecase.port.OutboxWriter;

/**
 * Creating, editing and withdrawing a draft.
 *
 * <p>These three operations share a transaction shape, a repository and the same
 * product-rule check, so they live together rather than in three classes that
 * would each hold the same four dependencies.
 */
@Service
public class ManageApplicationDraftUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageApplicationDraftUseCase.class);

    private final LoanApplicationRepository applications;
    private final ApplicantPseudonymiser pseudonymiser;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ManageApplicationDraftUseCase(
            LoanApplicationRepository applications,
            ApplicantPseudonymiser pseudonymiser,
            OutboxWriter outbox,
            Clock clock) {
        this.applications = applications;
        this.pseudonymiser = pseudonymiser;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates a draft.
     *
     * <p>The product rules are checked at creation as well as at submission. The
     * caller gets immediate, actionable feedback instead of filling in an entire
     * application and being rejected at the end for a limit they could have been
     * told about at the start.
     */
    @Transactional
    public LoanApplication createDraft(ApplicantDetails applicant, LoanRequest request, String correlationId) {
        ProductRules.validate(request, applicant);

        ApplicantReference reference = pseudonymiser.pseudonymise(applicant);
        LoanApplication application =
                LoanApplication.createDraft(ApplicationId.newId(), applicant, reference, request, clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.info("Draft created. applicationId={} correlationId={}", application.id(), correlationId);
        return application;
    }

    /** Replaces the content of an existing draft. Refused once submitted. */
    @Transactional
    public LoanApplication updateDraft(
            ApplicationId applicationId, ApplicantDetails applicant, LoanRequest request, String correlationId) {
        ProductRules.validate(request, applicant);

        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        application.updateDraft(applicant, request, clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.info(
                "Draft updated. applicationId={} version={} correlationId={}",
                applicationId,
                application.version(),
                correlationId);
        return application;
    }

    /** Withdraws a draft before submission. */
    @Transactional
    public LoanApplication cancel(ApplicationId applicationId, String reasonCode, String correlationId) {
        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        application.cancel(reasonCode, clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.info(
                "Application cancelled. applicationId={} reasonCode={} correlationId={}",
                applicationId,
                reasonCode,
                correlationId);
        return application;
    }
}
