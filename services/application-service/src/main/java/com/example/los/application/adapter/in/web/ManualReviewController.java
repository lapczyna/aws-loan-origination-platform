package com.example.los.application.adapter.in.web;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.los.application.adapter.in.web.dto.ApplicationResponse;
import com.example.los.application.adapter.in.web.dto.PageResponse;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.DecisionType;
import com.example.los.application.observability.CorrelationId;
import com.example.los.application.usecase.service.ApplicationQueryUseCase;
import com.example.los.application.usecase.service.RecordAssessmentOutcomeUseCase;

/**
 * The manual-review queue and the endpoint reviewers decide through.
 *
 * <p>Separately scoped from the applicant-facing API. A partner client that can
 * create and submit applications must not be able to approve them, so
 * {@code manual-review:*} scopes are granted only to internal reviewer clients.
 */
@RestController
@RequestMapping("/v1/manual-review")
class ManualReviewController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ApplicationQueryUseCase queries;
    private final RecordAssessmentOutcomeUseCase outcomes;

    ManualReviewController(ApplicationQueryUseCase queries, RecordAssessmentOutcomeUseCase outcomes) {
        this.queries = queries;
        this.outcomes = outcomes;
    }

    /**
     * Applications awaiting a human decision, oldest first.
     *
     * <p>Paginated and bounded. An unbounded queue endpoint is a denial-of-service
     * vector against the platform's own database as much as against the caller.
     */
    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('SCOPE_manual-review:read')")
    PageResponse<ApplicationResponse> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        ApplicationQueryUseCase.Page result =
                queries.findByStatus(ApplicationStatus.MANUAL_REVIEW, page, size);

        return new PageResponse<>(
                result.items().stream().map(ApplicationResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalItems(),
                result.totalPages(),
                result.hasNext());
    }

    /**
     * Records a reviewer's decision.
     *
     * @param taskId the application under review; the queue is a projection of
     *               applications, so a task identifier is an application identifier
     */
    @PostMapping("/tasks/{taskId}/decisions")
    @PreAuthorize("hasAuthority('SCOPE_manual-review:decide')")
    ApplicationResponse decide(
            @PathVariable String taskId,
            @Valid @RequestBody ReviewDecisionRequest request,
            @AuthenticationPrincipal Jwt reviewer) {

        return ApplicationResponse.from(outcomes.recordManualReviewDecision(
                ApplicationId.of(taskId),
                DecisionType.valueOf(request.decision()),
                // The reviewer is identified by the token subject, never by a
                // name or an email address, and never by a value the client sends
                // in the body: a caller must not be able to attribute a decision
                // to someone else.
                reviewer.getSubject(),
                CorrelationId.current()));
    }

    /**
     * A reviewer's decision.
     *
     * <p>{@code MANUAL_REVIEW} is deliberately not an accepted value: a reviewer
     * cannot send an application back to the queue it came from, which would let
     * an application loop indefinitely without anyone owning it.
     *
     * <p>There is deliberately no free-text notes field. Reviewer notes would
     * flow into the decision record and from there into events and the audit
     * store, where free text about a named individual is precisely what the
     * platform's privacy rules exclude. Capturing reviewer rationale properly
     * needs a separate, access-controlled store with its own retention rules;
     * bolting a text column onto this endpoint would look like the feature while
     * quietly breaking the guarantee.
     *
     * @param decision APPROVED or REJECTED
     */
    record ReviewDecisionRequest(
            @NotBlank(message = "decision is required")
            @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "decision must be APPROVED or REJECTED")
            String decision) {}
}
