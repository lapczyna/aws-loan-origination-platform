package com.example.los.application.adapter.out.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.usecase.port.ApplicantPseudonymiser;

/**
 * Derives applicant pseudonyms using a pepper read from the filesystem.
 *
 * <p>The pepper is supplied as a FILE, not as an environment variable or a
 * property. In AWS the file is projected into the pod by the Secrets Store CSI
 * driver from AWS Secrets Manager; locally it is generated into a gitignored
 * directory by {@code scripts/generate-local-secrets.sh}.
 *
 * <p>Files are used deliberately. An environment variable is visible in
 * {@code /proc}, in {@code docker inspect}, in a Kubernetes pod spec and in any
 * crash dump that captures the environment; a mounted file is readable only by
 * the process that opens it and can be rotated without rebuilding the pod spec.
 *
 * <p>The pepper is read once at startup and held in memory. It is never logged,
 * never included in an error message and never exposed through an actuator
 * endpoint. If it is missing or too short the service fails to start rather than
 * falling back to a default: a hard-coded fallback would silently make every
 * pseudonym on the platform reversible by anyone who can read the source.
 */
@Component
class SecretsManagerPseudonymiser implements ApplicantPseudonymiser {

    private static final int MINIMUM_PEPPER_BYTES = 32;

    private final byte[] pepper;

    SecretsManagerPseudonymiser(@Value("${los.security.applicant-pepper-file}") Path pepperFile) {
        this.pepper = readPepper(pepperFile);
    }

    @Override
    public ApplicantReference pseudonymise(ApplicantDetails details) {
        return ApplicantReference.deriveFrom(details, pepper);
    }

    private static byte[] readPepper(Path pepperFile) {
        byte[] contents;
        try {
            contents = Files.readString(pepperFile, StandardCharsets.UTF_8)
                    .strip()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The path is safe to name: it is deployment configuration, not a
            // secret. The contents are of course never mentioned.
            throw new IllegalStateException(
                    "Applicant reference pepper could not be read from " + pepperFile
                            + ". The service cannot start without it.",
                    e);
        }

        if (contents.length < MINIMUM_PEPPER_BYTES) {
            throw new IllegalStateException("Applicant reference pepper must be at least " + MINIMUM_PEPPER_BYTES
                    + " bytes; a shorter value makes pseudonyms brute-forceable.");
        }
        return contents;
    }
}
