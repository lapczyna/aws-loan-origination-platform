package com.example.los.application.usecase.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.exception.ApplicationNotFoundException;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.Decision;
import com.example.los.application.domain.model.DecisionType;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.ReasonCodes;
import com.example.los.application.usecase.port.LoanApplicationRepository;
import com.example.los.application.usecase.port.OutboxWriter;
import com.example.los.application.usecase.port.ProcessedEventStore;
import com.example.los.events.vocabulary.Recommendation;

/**
 * Applies the workflow's outcome to the application.
 *
 * <p>Driven by events from the workflow context, so it must cope with the two
 * realities of at-least-once delivery: the same event arriving twice, and events
 * arriving in an order the producer did not intend.
 *
 * <h2>Duplicates</h2>
 *
 * <p>Every entry point claims the event identifier in the de-duplication ledger
 * inside the same transaction as the business effect. A duplicate fails the
 * claim and returns without doing anything, so a redelivered "approve" cannot
 * approve an application twice or emit a second decision event.
 *
 * <h2>Out-of-order and stale events</h2>
 *
 * <p>Two guards, and they catch different things:
 *
 * <ul>
 *   <li>The <b>state machine</b> refuses a transition that does not make sense
 *       from the current state. A "workflow completed" for an application that
 *       already reached a terminal decision is rejected outright.
 *   <li>The <b>aggregate version</b> catches a stale event that would otherwise
 *       be a legal transition. An event describing a version older than the one
 *       stored is a redelivery of something already superseded, and applying it
 *       would move the application backwards.
 * </ul>
 *
 * <p>Neither case is an error. Both are expected traffic on an at-least-once
 * bus, so both are recorded and ignored rather than retried or dead-lettered.
 */
@Service
public class RecordAssessmentOutcomeUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordAssessmentOutcomeUseCase.class);
    private static final String CONSUMER = "application-service.workflow-outcomes";

    private final LoanApplicationRepository applications;
    private final OutboxWriter outbox;
    private final ProcessedEventStore processedEvents;
    private final Clock clock;

    public RecordAssessmentOutcomeUseCase(
            LoanApplicationRepository applications,
            OutboxWriter outbox,
            ProcessedEventStore processedEvents,
            Clock clock) {
        this.applications = applications;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** Outcome of applying an inbound event. Used for metrics and for tests. */
    public enum Outcome {
        APPLIED,
        DUPLICATE_IGNORED,
        STALE_IGNORED,
        NOT_APPLICABLE
    }

    /**
     * Moves the application out of CHECKS_IN_PROGRESS according to the workflow's
     * recommendation.
     *
     * @param eventId               the inbound event identifier, used for de-duplication
     * @param sourceAggregateVersion version of the application the workflow assessed
     */
    @Transactional
    public Outcome recordWorkflowRecommendation(
            String eventId,
            ApplicationId applicationId,
            Recommendation recommendation,
            String reasonCode,
            long sourceAggregateVersion,
            String correlationId) {

        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("Duplicate workflow event ignored. eventId={} applicationId={}", eventId, applicationId);
            return Outcome.DUPLICATE_IGNORED;
        }

        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (sourceAggregateVersion < application.version()) {
            log.info(
                    "Stale workflow event ignored. applicationId={} eventVersion={} currentVersion={} correlationId={}",
                    applicationId,
                    sourceAggregateVersion,
                    application.version(),
                    correlationId);
            return Outcome.STALE_IGNORED;
        }

        if (application.status() != ApplicationStatus.CHECKS_IN_PROGRESS) {
            log.info(
                    "Workflow event does not apply in the current state. applicationId={} status={} correlationId={}",
                    applicationId,
                    application.status(),
                    correlationId);
            return Outcome.NOT_APPLICABLE;
        }

        Decision decision = Decision.automatic(toDecisionType(recommendation), reasonCode);
        application.recordDecision(decision, clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.info(
                "Assessment outcome applied. applicationId={} decision={} reasonCode={} correlationId={}",
                applicationId,
                decision.type(),
                reasonCode,
                correlationId);
        return Outcome.APPLIED;
    }

    /**
     * Marks the application as technically failed after the workflow gave up.
     *
     * <p>A failure, not a rejection: the applicant was never assessed, so nothing
     * has been decided about them.
     */
    @Transactional
    public Outcome recordWorkflowFailure(
            String eventId, ApplicationId applicationId, String errorCode, String correlationId) {

        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            return Outcome.DUPLICATE_IGNORED;
        }

        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (application.status().isTerminal()) {
            return Outcome.NOT_APPLICABLE;
        }

        application.fail(ReasonCodes.ASSESSMENT_FAILED, clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.error(
                "Assessment failed permanently. applicationId={} errorCode={} correlationId={}",
                applicationId,
                errorCode,
                correlationId);
        return Outcome.APPLIED;
    }

    /**
     * Records a human reviewer's decision on an application in manual review.
     *
     * @param reviewerReference pseudonymous reviewer identifier, never a name or an email address
     */
    @Transactional
    public LoanApplication recordManualReviewDecision(
            ApplicationId applicationId, DecisionType decisionType, String reviewerReference, String correlationId) {

        LoanApplication application = applications
                .findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        // The aggregate refuses this unless the application is genuinely awaiting
        // review, so a reviewer cannot overrule a decision already taken.
        application.recordDecision(
                new Decision(decisionType, ReasonCodes.REVIEWER_DECISION, reviewerReference), clock.instant());

        applications.save(application);
        outbox.append(application.drainPendingEvents(), correlationId);

        log.info(
                "Manual review decision recorded. applicationId={} decision={} reviewer={} correlationId={}",
                applicationId,
                decisionType,
                reviewerReference,
                correlationId);
        return application;
    }

    private static DecisionType toDecisionType(Recommendation recommendation) {
        return switch (recommendation) {
            case APPROVE -> DecisionType.APPROVED;
            case REJECT -> DecisionType.REJECTED;
            case MANUAL_REVIEW -> DecisionType.MANUAL_REVIEW;
        };
    }
}
