package com.example.los.application.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.los.application.domain.event.ApplicationCreated;
import com.example.los.application.domain.event.ApplicationDomainEvent;
import com.example.los.application.domain.event.ApplicationSubmitted;
import com.example.los.application.domain.event.DecisionRecorded;
import com.example.los.application.domain.event.StatusChanged;
import com.example.los.application.domain.exception.ApplicationNotEditableException;
import com.example.los.application.domain.exception.IllegalStateTransitionException;
import com.example.los.application.domain.exception.MandatoryDocumentsMissingException;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.Decision;
import com.example.los.application.domain.model.DecisionType;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.ReasonCodes;
import com.example.los.events.vocabulary.DocumentType;

import static com.example.los.application.domain.SyntheticApplications.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour of the loan application aggregate. */
class LoanApplicationTest {

    @Nested
    @DisplayName("creating a draft")
    class CreatingADraft {

        @Test
        @DisplayName("starts in DRAFT at version 1 and records that it was created")
        void draftStartsInDraftState() {
            LoanApplication application = SyntheticApplications.draft();

            assertThat(application.status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(application.version()).isEqualTo(1L);
            assertThat(application.submittedAt()).isNull();
            assertThat(application.pendingEvents()).singleElement().isInstanceOf(ApplicationCreated.class);
        }

        @Test
        @DisplayName("does not print applicant data when logged")
        void toStringDoesNotLeakApplicantData() {
            LoanApplication application = SyntheticApplications.draft();

            String printed = application.toString();

            assertThat(printed)
                    .contains(application.id().toString())
                    .doesNotContain("Placeholder")
                    .doesNotContain("example.com")
                    .doesNotContain("1990-04-17");
        }
    }

    @Nested
    @DisplayName("editing a draft")
    class EditingADraft {

        @Test
        @DisplayName("replaces the content and increments the version")
        void updatingADraftIncrementsTheVersion() {
            LoanApplication application = SyntheticApplications.draft();
            long versionBefore = application.version();

            application.updateDraft(
                    SyntheticApplications.applicant(),
                    SyntheticApplications.requestOf(2_000_000L, 6_000_000L, 60),
                    NOW);

            assertThat(application.version()).isEqualTo(versionBefore + 1);
            assertThat(application.request().term().months()).isEqualTo(60);
        }

        @Test
        @DisplayName("is refused once the application has been submitted")
        void editingAfterSubmissionIsRefused() {
            LoanApplication application = SyntheticApplications.submitted();

            assertThatThrownBy(() -> application.updateDraft(
                            SyntheticApplications.applicant(), SyntheticApplications.request(), NOW))
                    .isInstanceOf(ApplicationNotEditableException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ApplicationNotEditableException.ERROR_CODE);
        }
    }

    @Nested
    @DisplayName("submitting")
    class Submitting {

        @Test
        @DisplayName("moves to SUBMITTED and records both the transition and the submission")
        void submissionRecordsBothEvents() {
            LoanApplication application = SyntheticApplications.draft();

            application.submit(SyntheticApplications.allMandatoryDocumentsClean(), NOW);

            assertThat(application.status()).isEqualTo(ApplicationStatus.SUBMITTED);
            assertThat(application.submittedAt()).isEqualTo(NOW);
            assertThat(application.pendingEvents())
                    .hasAtLeastOneElementOfType(StatusChanged.class)
                    .hasAtLeastOneElementOfType(ApplicationSubmitted.class);
        }

        @Test
        @DisplayName("is refused when a mandatory document has not been scanned clean")
        void submissionRequiresEveryMandatoryDocument() {
            LoanApplication application = SyntheticApplications.draft();
            Set<DocumentType> onlyOneMandatoryDocument = EnumSet.of(DocumentType.PROOF_OF_IDENTITY);

            assertThatThrownBy(() -> application.submit(onlyOneMandatoryDocument, NOW))
                    .isInstanceOf(MandatoryDocumentsMissingException.class)
                    .extracting(e -> ((MandatoryDocumentsMissingException) e).missing())
                    .isEqualTo(Set.of(DocumentType.PROOF_OF_INCOME));
        }

        @Test
        @DisplayName("leaves the application untouched when it is refused")
        void refusedSubmissionDoesNotMutateTheAggregate() {
            LoanApplication application = SyntheticApplications.draft();
            long versionBefore = application.version();

            assertThatThrownBy(() -> application.submit(Set.of(), NOW))
                    .isInstanceOf(MandatoryDocumentsMissingException.class);

            assertThat(application.status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(application.version()).isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("cannot be repeated")
        void submittingTwiceIsRefused() {
            LoanApplication application = SyntheticApplications.submitted();

            assertThatThrownBy(() -> application.submit(SyntheticApplications.allMandatoryDocumentsClean(), NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("is refused for an application that was already cancelled")
        void submittingACancelledApplicationIsRefused() {
            LoanApplication application = SyntheticApplications.draft();
            application.cancel(ReasonCodes.WITHDRAWN_BY_APPLICANT, NOW);

            assertThatThrownBy(() -> application.submit(SyntheticApplications.allMandatoryDocumentsClean(), NOW))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasFieldOrPropertyWithValue("from", ApplicationStatus.CANCELLED)
                    .hasFieldOrPropertyWithValue("to", ApplicationStatus.SUBMITTED);
        }
    }

    @Nested
    @DisplayName("recording a decision")
    class RecordingADecision {

        @Test
        @DisplayName("approving from CHECKS_IN_PROGRESS reaches APPROVED and records the decision")
        void approvalReachesApprovedState() {
            LoanApplication application = SyntheticApplications.inChecks();

            application.recordDecision(
                    Decision.automatic(DecisionType.APPROVED, ReasonCodes.ALL_CHECKS_PASSED), NOW);

            assertThat(application.status()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(application.decision().isAutomatic()).isTrue();
            assertThat(application.pendingEvents()).hasAtLeastOneElementOfType(DecisionRecorded.class);
        }

        @Test
        @DisplayName("routing to manual review is not a terminal decision")
        void manualReviewIsNotTerminal() {
            LoanApplication application = SyntheticApplications.inChecks();

            application.recordDecision(
                    Decision.automatic(DecisionType.MANUAL_REVIEW, ReasonCodes.MANUAL_REVIEW_REQUIRED), NOW);

            assertThat(application.status()).isEqualTo(ApplicationStatus.MANUAL_REVIEW);
            assertThat(application.status().isTerminal()).isFalse();
        }

        @Test
        @DisplayName("a reviewer can approve an application that was sent to manual review")
        void reviewerCanApproveAfterManualReview() {
            LoanApplication application = SyntheticApplications.inChecks();
            application.recordDecision(
                    Decision.automatic(DecisionType.MANUAL_REVIEW, ReasonCodes.MANUAL_REVIEW_REQUIRED), NOW);

            application.recordDecision(
                    new Decision(DecisionType.APPROVED, ReasonCodes.REVIEWER_DECISION, "REV-0001"), NOW);

            assertThat(application.status()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(application.decision().isAutomatic()).isFalse();
            assertThat(application.decision().decidedBy()).isEqualTo("REV-0001");
        }

        @Test
        @DisplayName("is refused for an application that has not started its checks")
        void decidingADraftIsRefused() {
            LoanApplication application = SyntheticApplications.draft();

            assertThatThrownBy(() -> application.recordDecision(
                            Decision.automatic(DecisionType.APPROVED, ReasonCodes.ALL_CHECKS_PASSED), NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("cannot overwrite a decision that has already been made")
        void decidingTwiceIsRefused() {
            LoanApplication application = SyntheticApplications.inChecks();
            application.recordDecision(Decision.automatic(DecisionType.REJECTED, ReasonCodes.CHECK_FAILED), NOW);

            assertThatThrownBy(() -> application.recordDecision(
                            Decision.automatic(DecisionType.APPROVED, ReasonCodes.ALL_CHECKS_PASSED), NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("event handling")
    class EventHandling {

        @Test
        @DisplayName("every event carries the aggregate version it produced, in order")
        void eventVersionsIncreaseMonotonically() {
            LoanApplication application = SyntheticApplications.draft();
            application.submit(SyntheticApplications.allMandatoryDocumentsClean(), NOW);
            application.beginValidation(NOW);
            application.beginChecks(NOW);

            List<Long> versions = application.pendingEvents().stream()
                    .map(ApplicationDomainEvent::aggregateVersion)
                    .toList();

            assertThat(versions).isSorted();
            assertThat(versions.getLast()).isEqualTo(application.version());
        }

        @Test
        @DisplayName("draining returns the events once and leaves the aggregate empty")
        void drainingIsIdempotent() {
            LoanApplication application = SyntheticApplications.submitted();

            List<ApplicationDomainEvent> first = application.drainPendingEvents();
            List<ApplicationDomainEvent> second = application.drainPendingEvents();

            assertThat(first).isNotEmpty();
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("the pending event list cannot be modified from outside the aggregate")
        void pendingEventsAreImmutable() {
            LoanApplication application = SyntheticApplications.draft();

            assertThatThrownBy(() -> application.pendingEvents().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("a technical failure is distinct from a business rejection")
    void failureIsDistinctFromRejection() {
        LoanApplication application = SyntheticApplications.inChecks();

        application.fail(ReasonCodes.ASSESSMENT_FAILED, NOW);

        assertThat(application.status()).isEqualTo(ApplicationStatus.FAILED);
        assertThat(application.decision()).isNull();
    }

    @Test
    @DisplayName("reconstituting from storage raises no events")
    void reconstitutionRaisesNoEvents() {
        LoanApplication original = SyntheticApplications.submitted();
        original.drainPendingEvents();

        LoanApplication restored = LoanApplication.reconstitute(
                original.id(),
                original.applicantReference(),
                original.applicant(),
                original.request(),
                original.status(),
                original.decision(),
                original.createdAt(),
                original.submittedAt(),
                original.updatedAt(),
                original.version());

        assertThat(restored.pendingEvents()).isEmpty();
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.version()).isEqualTo(original.version());
    }

    @Test
    @DisplayName("timestamps are recorded exactly as supplied, never read from the system clock")
    void timeIsInjectedNotRead() {
        // The aggregate never calls Instant.now(). Time is a parameter, which is
        // what makes the lifecycle deterministically testable and lets the whole
        // platform agree on a single clock.
        Instant fixed = Instant.parse("2030-06-01T12:00:00Z");
        LoanApplication application = SyntheticApplications.draft();

        application.updateDraft(SyntheticApplications.applicant(), SyntheticApplications.request(), fixed);

        assertThat(application.updatedAt()).isEqualTo(fixed);
    }
}
