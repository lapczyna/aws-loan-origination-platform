package com.example.los.application.usecase.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.exception.ApplicationNotFoundException;
import com.example.los.application.domain.exception.ProductRuleViolationException;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.Decision;
import com.example.los.application.domain.model.DecisionType;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.ProductRules;
import com.example.los.application.usecase.port.DocumentStatusPort;
import com.example.los.application.usecase.port.LoanApplicationRepository;
import com.example.los.application.usecase.port.OutboxWriter;
import com.example.los.events.vocabulary.DocumentType;

/**
 * Submits an application for assessment.
 *
 * <p>This is the transaction the whole outbox design exists for. In one
 * PostgreSQL transaction it:
 *
 * <ol>
 *   <li>loads the aggregate,
 *   <li>asks the document context which documents have been scanned clean,
 *   <li>performs the DRAFT to SUBMITTED transition, which refuses if a mandatory
 *       document is missing or the application is in the wrong state,
 *   <li>runs synchronous validation (SUBMITTED to VALIDATING),
 *   <li>either records a business rejection or requests the external checks
 *       (VALIDATING to CHECKS_IN_PROGRESS),
 *   <li>persists the aggregate and appends to the immutable version history,
 *   <li>writes every resulting event to the outbox,
 * </ol>
 *
 * <p>and commits. Either all of it happened or none of it did. There is no state
 * in which an application is submitted but no event was recorded, and none in
 * which an event was published for a submission that rolled back.
 *
 * <h2>Why VALIDATING is a real state and not a formality</h2>
 *
 * <p>Validation is genuinely synchronous and takes microseconds, so the
 * application passes through VALIDATING inside this transaction. It is still
 * modelled as a distinct state because that is where the transition to REJECTED
 * legitimately originates: an application that fails a product rule was assessed
 * and declined, which is a different fact from one that was never assessed. Both
 * transitions are recorded in the version history and published, so the audit
 * trail shows how the decision was reached rather than just its result.
 *
 * <h2>Why a rule violation here is not an HTTP error</h2>
 *
 * <p>{@link ManageApplicationDraftUseCase#createDraft} refuses to create a draft
 * that violates the product rules, so a violation there is a 400: the caller
 * asked for something the product does not offer. By submission time the draft
 * already exists, and a violation means the rules changed underneath it or the
 * draft was edited into an ineligible shape. That is a business outcome about a
 * real application, so it is recorded as a rejection with a reason code, not
 * thrown away as a request error.
 *
 * <h2>Concurrency and idempotency</h2>
 *
 * <p>Optimistic locking makes concurrent submissions safe: two simultaneous
 * submits both read version N and only one can write N+1, so the loser gets a
 * conflict rather than a duplicate transition. The idempotency record is written
 * by the web adapter inside this same transaction, so a client retry cannot
 * produce a second submission. Idempotency lives in the adapter because it is a
 * property of the HTTP interaction: the same use case driven from a Kafka
 * consumer would de-duplicate on an event identifier instead.
 */
@Service
public class SubmitApplicationUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitApplicationUseCase.class);

    private final LoanApplicationRepository applications;
    private final DocumentStatusPort documents;
    private final OutboxWriter outbox;
    private final Clock clock;

    public SubmitApplicationUseCase(
            LoanApplicationRepository applications,
            DocumentStatusPort documents,
            OutboxWriter outbox,
            Clock clock) {
        this.applications = applications;
        this.documents = documents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @param applicationId the application to submit
     * @param correlationId identifier of the business interaction, carried onto every event
     * @return the aggregate, either awaiting checks or already rejected by validation
     */
    @Transactional
    public LoanApplication submit(ApplicationId applicationId, String correlationId) {
        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        Set<DocumentType> cleanDocuments = documents.cleanDocumentTypes(applicationId);
        Instant now = clock.instant();

        // The aggregate enforces its own preconditions: the wrong state and a
        // missing mandatory document are both refused here, not by this class.
        application.submit(cleanDocuments, now);
        application.beginValidation(now);

        try {
            ProductRules.validate(application.request(), application.applicant());
            application.beginChecks(now);
        } catch (ProductRuleViolationException violation) {
            // A business outcome, not a technical failure. Recorded on the
            // application with a stable reason code and never retried.
            application.recordDecision(
                    Decision.automatic(DecisionType.REJECTED, violation.errorCode()), now);
        }

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        // Identifiers, a status and a count. No applicant data, no amounts, no
        // document metadata.
        log.info(
                "Application submitted. applicationId={} status={} version={} cleanDocuments={} correlationId={}",
                applicationId,
                application.status(),
                application.version(),
                cleanDocuments.size(),
                correlationId);

        return application;
    }
}
