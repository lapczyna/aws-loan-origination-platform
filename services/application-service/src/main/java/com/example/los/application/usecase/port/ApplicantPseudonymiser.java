package com.example.los.application.usecase.port;

import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicantReference;

/**
 * Outbound port that derives an applicant's pseudonym.
 *
 * <p>A port rather than a static call because derivation needs a secret pepper
 * from AWS Secrets Manager, and the domain must not reach for infrastructure.
 * Keeping it behind a port also means the secret is held in exactly one object
 * whose lifecycle and rotation can be reasoned about.
 */
public interface ApplicantPseudonymiser {

    ApplicantReference pseudonymise(ApplicantDetails details);
}
