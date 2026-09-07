package com.example.los.workflow.usecase.port;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.AssessmentInputs;

/**
 * Outbound port for one external assessment provider.
 *
 * <p>One port per {@link CheckType}, chosen at runtime by {@link #supports()}.
 * A single "run any check" port would force every implementation to carry a
 * switch over the check type, and would make it impossible to give KYC and
 * credit scoring different timeouts, retry budgets and circuit breakers — which
 * they need, because they are different systems with different failure
 * characteristics.
 *
 * <p><strong>Every implementation in this repository is a SIMULATOR.</strong> No
 * real KYC, AML, fraud or credit bureau system is contacted anywhere. The port
 * exists so that a real adapter can be substituted without the workflow, the
 * policy or the persistence layer changing at all.
 *
 * <h2>The contract implementations must honour</h2>
 *
 * <ul>
 *   <li>Return a {@link CheckAnswer} when the provider gave a definitive answer,
 *       including a business refusal. A refusal is a result, not an error.
 *   <li>Throw {@link TransientCheckFailure} when the provider could not be
 *       reached or did not answer. The workflow will retry.
 *   <li>Throw {@link PermanentCheckFailure} when the provider rejected the
 *       request in a way that retrying cannot fix — a malformed request, a
 *       revoked credential, an unsupported product. The workflow will not retry.
 * </ul>
 *
 * <p>Getting that distinction wrong is expensive in both directions: retrying a
 * permanent failure burns the budget and delays the applicant for no reason,
 * while treating a transient failure as permanent fails an assessment that would
 * have succeeded seconds later.
 */
public interface ExternalCheckPort {

    /** The check this provider performs. */
    CheckType supports();

    /**
     * A definitive answer from the provider.
     *
     * @param outcome    passed, failed or inconclusive
     * @param reasonCode stable, safe reason code; never free text from the provider
     * @param score      normalised 0-1000 risk score where the check produces one
     */
    record CheckAnswer(CheckOutcome outcome, String reasonCode, Integer score) {}

    /**
     * The request sent to the provider.
     *
     * <p>Carries the pseudonymous applicant reference and the risk-relevant
     * figures — never a name, an email address or a date of birth. A real
     * provider that required identity attributes would receive them through a
     * separate, tightly scoped path; this port deliberately cannot carry them.
     */
    record CheckRequest(String applicationId, String applicantReference, AssessmentInputs inputs, int attempt) {}

    /**
     * Performs the check.
     *
     * @throws TransientCheckFailure when the provider could not be reached
     * @throws PermanentCheckFailure when retrying cannot help
     */
    CheckAnswer perform(CheckRequest request);
}
