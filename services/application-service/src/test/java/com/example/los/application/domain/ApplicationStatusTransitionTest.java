package com.example.los.application.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.los.application.domain.model.ApplicationStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive tests of the lifecycle state machine.
 *
 * <p>Rather than asserting a handful of interesting transitions, these tests
 * enumerate the whole 9x9 matrix and assert that exactly the declared
 * transitions are permitted. That way a transition added by accident, or one
 * removed by a refactor, fails the build instead of silently changing what the
 * platform allows.
 */
class ApplicationStatusTransitionTest {

    /** The complete set of transitions this platform permits, written out independently. */
    private static final Set<String> EXPECTED_LEGAL_TRANSITIONS = Set.of(
            "DRAFT->SUBMITTED",
            "DRAFT->CANCELLED",
            "SUBMITTED->VALIDATING",
            "SUBMITTED->FAILED",
            "VALIDATING->CHECKS_IN_PROGRESS",
            "VALIDATING->REJECTED",
            "VALIDATING->FAILED",
            "CHECKS_IN_PROGRESS->APPROVED",
            "CHECKS_IN_PROGRESS->REJECTED",
            "CHECKS_IN_PROGRESS->MANUAL_REVIEW",
            "CHECKS_IN_PROGRESS->FAILED",
            "MANUAL_REVIEW->APPROVED",
            "MANUAL_REVIEW->REJECTED",
            "MANUAL_REVIEW->FAILED");

    static Stream<org.junit.jupiter.params.provider.Arguments> everyPossibleTransition() {
        List<org.junit.jupiter.params.provider.Arguments> all = new ArrayList<>();
        for (ApplicationStatus from : ApplicationStatus.values()) {
            for (ApplicationStatus to : ApplicationStatus.values()) {
                all.add(org.junit.jupiter.params.provider.Arguments.of(from, to));
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPossibleTransition")
    @DisplayName("exactly the declared transitions are permitted, and no others")
    void transitionMatrixMatchesTheDeclaredRules(ApplicationStatus from, ApplicationStatus to) {
        boolean expected = EXPECTED_LEGAL_TRANSITIONS.contains(from + "->" + to);

        assertThat(from.canTransitionTo(to))
                .withFailMessage(
                        "Transition %s -> %s is %s by the state machine but %s by the declared rules.",
                        from,
                        to,
                        from.canTransitionTo(to) ? "allowed" : "refused",
                        expected ? "expected to be allowed" : "expected to be refused")
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    @DisplayName("no status can transition to itself")
    void noSelfTransitions(ApplicationStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Nested
    @DisplayName("terminal statuses")
    class TerminalStatuses {

        @ParameterizedTest
        @EnumSource(
                value = ApplicationStatus.class,
                names = {"APPROVED", "REJECTED", "FAILED", "CANCELLED"})
        @DisplayName("are terminal and permit no onward transition")
        void terminalStatusesHaveNoOnwardTransitions(ApplicationStatus status) {
            assertThat(status.isTerminal()).isTrue();
            assertThat(status.allowedTransitions()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(
                value = ApplicationStatus.class,
                names = {"DRAFT", "SUBMITTED", "VALIDATING", "CHECKS_IN_PROGRESS", "MANUAL_REVIEW"})
        @DisplayName("every non-terminal status can still reach a terminal one")
        void nonTerminalStatusesCanReachATerminalStatus(ApplicationStatus status) {
            assertThat(status.isTerminal()).isFalse();
            assertThat(reachableFrom(status)).anyMatch(ApplicationStatus::isTerminal);
        }
    }

    @Test
    @DisplayName("every status is reachable from DRAFT, so no status is dead code")
    void everyStatusIsReachableFromDraft() {
        Set<ApplicationStatus> reachable = reachableFrom(ApplicationStatus.DRAFT);
        reachable.add(ApplicationStatus.DRAFT);

        assertThat(reachable).containsExactlyInAnyOrder(ApplicationStatus.values());
    }

    @Test
    @DisplayName("only DRAFT is editable, so content cannot change after submission")
    void onlyDraftIsEditable() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(status.isEditable()).isEqualTo(status == ApplicationStatus.DRAFT);
        }
    }

    @Test
    @DisplayName("a cancelled application cannot be resurrected")
    void cancellationIsFinal() {
        assertThat(ApplicationStatus.CANCELLED.allowedTransitions()).isEmpty();
    }

    /** Breadth-first closure of the transition graph. */
    private static Set<ApplicationStatus> reachableFrom(ApplicationStatus start) {
        Set<ApplicationStatus> seen = EnumSet.noneOf(ApplicationStatus.class);
        List<ApplicationStatus> frontier = new ArrayList<>(start.allowedTransitions());
        while (!frontier.isEmpty()) {
            ApplicationStatus current = frontier.removeFirst();
            if (seen.add(current)) {
                frontier.addAll(current.allowedTransitions());
            }
        }
        return seen;
    }
}
