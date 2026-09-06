package com.example.los.application.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.example.los.application.domain.event.ApplicationCancelled;
import com.example.los.application.domain.event.ApplicationCreated;
import com.example.los.application.domain.event.ApplicationDomainEvent;
import com.example.los.application.domain.event.ApplicationSubmitted;
import com.example.los.application.domain.event.DecisionRecorded;
import com.example.los.application.domain.event.DraftUpdated;
import com.example.los.application.domain.event.StatusChanged;
import com.example.los.application.domain.exception.ApplicationNotEditableException;
import com.example.los.application.domain.exception.IllegalStateTransitionException;
import com.example.los.application.domain.exception.MandatoryDocumentsMissingException;
import com.example.los.events.vocabulary.DocumentType;

/**
 * The loan application aggregate: the consistency boundary and the only place
 * where an application's lifecycle may change.
 *
 * <p>There are no setters. Every state change is a named business operation that
 * states its own preconditions and records what happened. A use case cannot put
 * an application into an inconsistent state by calling methods in the wrong
 * order, because each method refuses to run from the wrong state. This is the
 * difference between a model and a data holder with a service full of
 * procedural checks around it.
 *
 * <p>The aggregate is deliberately free of framework annotations. It knows
 * nothing about JPA, Spring or Jackson; {@code LoanApplicationEntity} in the
 * persistence adapter carries that concern, and a mapper moves between them.
 *
 * <h2>Versioning</h2>
 *
 * <p>{@link #version()} is the <em>business</em> version of the aggregate. It
 * increments once per state-changing operation and is published on every event,
 * so a consumer can detect an event that arrived out of order. It is distinct
 * from the JPA optimistic-lock version, which counts physical row writes.
 *
 * <h2>Events</h2>
 *
 * <p>Operations append to {@link #pendingEvents()}. The use case drains them
 * inside the same database transaction that persists the aggregate and writes
 * them to the outbox, which is what makes "state changed" and "event recorded"
 * atomic.
 */
public final class LoanApplication {

    private final ApplicationId id;
    private final ApplicantReference applicantReference;
    private final Instant createdAt;

    private ApplicantDetails applicant;
    private LoanRequest request;
    private ApplicationStatus status;
    private Decision decision;
    private Instant submittedAt;
    private Instant updatedAt;
    private long version;

    private final List<ApplicationDomainEvent> pendingEvents = new ArrayList<>();

    private LoanApplication(
            ApplicationId id,
            ApplicantReference applicantReference,
            ApplicantDetails applicant,
            LoanRequest request,
            ApplicationStatus status,
            Decision decision,
            Instant createdAt,
            Instant submittedAt,
            Instant updatedAt,
            long version) {
        this.id = id;
        this.applicantReference = applicantReference;
        this.applicant = applicant;
        this.request = request;
        this.status = status;
        this.decision = decision;
        this.createdAt = createdAt;
        this.submittedAt = submittedAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Creates a new draft.
     *
     * @param applicantReference pseudonym derived from {@code applicant}; passed
     *                           in rather than derived here because derivation
     *                           needs a secret the domain must not reach for
     */
    public static LoanApplication createDraft(
            ApplicationId id,
            ApplicantDetails applicant,
            ApplicantReference applicantReference,
            LoanRequest request,
            Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(applicant, "applicant must not be null");
        Objects.requireNonNull(applicantReference, "applicantReference must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(now, "now must not be null");

        LoanApplication application = new LoanApplication(
                id, applicantReference, applicant, request, ApplicationStatus.DRAFT, null, now, null, now, 1L);
        application.pendingEvents.add(new ApplicationCreated(id, applicantReference, 1L, now));
        return application;
    }

    /**
     * Rebuilds an aggregate from storage.
     *
     * <p>No events are raised: reconstitution is not a business event, it is the
     * persistence adapter handing back state that already happened.
     */
    public static LoanApplication reconstitute(
            ApplicationId id,
            ApplicantReference applicantReference,
            ApplicantDetails applicant,
            LoanRequest request,
            ApplicationStatus status,
            Decision decision,
            Instant createdAt,
            Instant submittedAt,
            Instant updatedAt,
            long version) {
        return new LoanApplication(
                id,
                applicantReference,
                applicant,
                request,
                status,
                decision,
                createdAt,
                submittedAt,
                updatedAt,
                version);
    }

    /** Replaces the content of a draft. Refused once the application is submitted. */
    public void updateDraft(ApplicantDetails newApplicant, LoanRequest newRequest, Instant now) {
        if (!status.isEditable()) {
            throw new ApplicationNotEditableException(status);
        }
        this.applicant = Objects.requireNonNull(newApplicant, "applicant must not be null");
        this.request = Objects.requireNonNull(newRequest, "request must not be null");
        this.updatedAt = now;
        this.version++;
        pendingEvents.add(new DraftUpdated(id, version, now));
    }

    /**
     * Submits the application for assessment.
     *
     * @param cleanDocumentTypes document categories that have passed scanning and
     *                           reached the CLEAN state
     * @throws MandatoryDocumentsMissingException when a mandatory document is not clean
     */
    public void submit(Set<DocumentType> cleanDocumentTypes, Instant now) {
        requireTransitionTo(ApplicationStatus.SUBMITTED);

        Set<DocumentType> missing = EnumSet.noneOf(DocumentType.class);
        for (DocumentType type : DocumentType.values()) {
            if (type.isMandatory() && !cleanDocumentTypes.contains(type)) {
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) {
            throw new MandatoryDocumentsMissingException(missing);
        }

        transitionTo(ApplicationStatus.SUBMITTED, ReasonCodes.SUBMISSION_ACCEPTED, now);
        this.submittedAt = now;
        pendingEvents.add(new ApplicationSubmitted(id, applicantReference, request, version, now));
    }

    /** Moves a submitted application into synchronous validation. */
    public void beginValidation(Instant now) {
        transitionTo(ApplicationStatus.VALIDATING, ReasonCodes.SUBMISSION_ACCEPTED, now);
    }

    /** Records that validation passed and the external checks have been requested. */
    public void beginChecks(Instant now) {
        transitionTo(ApplicationStatus.CHECKS_IN_PROGRESS, ReasonCodes.VALIDATION_PASSED, now);
    }

    /**
     * Records a decision and moves to the status that decision implies.
     *
     * <p>Rejecting during validation and rejecting after the checks are both
     * legal, and both go through here, so there is exactly one path by which an
     * application acquires a decision.
     */
    public void recordDecision(Decision newDecision, Instant now) {
        Objects.requireNonNull(newDecision, "decision must not be null");
        ApplicationStatus target = newDecision.resultingStatus();
        requireTransitionTo(target);

        this.decision = newDecision;
        transitionTo(target, newDecision.reasonCode(), now);
        pendingEvents.add(new DecisionRecorded(id, newDecision, version, now));
    }

    /**
     * Marks the application as technically failed.
     *
     * <p>Distinct from a rejection: it carries no judgement about the applicant
     * and it is the state the controlled-replay runbook acts on.
     */
    public void fail(String errorCode, Instant now) {
        transitionTo(ApplicationStatus.FAILED, errorCode, now);
    }

    /** Withdraws a draft. Only possible before submission. */
    public void cancel(String reasonCode, Instant now) {
        transitionTo(ApplicationStatus.CANCELLED, reasonCode, now);
        pendingEvents.add(new ApplicationCancelled(id, reasonCode, version, now));
    }

    private void transitionTo(ApplicationStatus target, String reasonCode, Instant now) {
        requireTransitionTo(target);
        ApplicationStatus previous = this.status;
        this.status = target;
        this.updatedAt = now;
        this.version++;
        pendingEvents.add(new StatusChanged(id, previous, target, reasonCode, version, now));
    }

    private void requireTransitionTo(ApplicationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
    }

    /**
     * Returns the events raised since the aggregate was loaded and clears them.
     *
     * <p>Draining rather than exposing prevents the same event being written to
     * the outbox twice if a use case saves the aggregate more than once.
     */
    public List<ApplicationDomainEvent> drainPendingEvents() {
        List<ApplicationDomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public List<ApplicationDomainEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public ApplicationId id() {
        return id;
    }

    public ApplicantReference applicantReference() {
        return applicantReference;
    }

    public ApplicantDetails applicant() {
        return applicant;
    }

    public LoanRequest request() {
        return request;
    }

    public ApplicationStatus status() {
        return status;
    }

    public Decision decision() {
        return decision;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /**
     * Redacted on purpose: the aggregate holds {@link ApplicantDetails}, so a
     * default {@code toString()} reaching a log statement would print personal
     * data. Only the identifier, the status and the version are ever printed.
     */
    @Override
    public String toString() {
        return "LoanApplication[id=" + id + ", status=" + status + ", version=" + version + "]";
    }
}
