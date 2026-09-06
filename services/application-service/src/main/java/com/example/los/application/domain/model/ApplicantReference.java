package com.example.los.application.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An irreversible pseudonym for an applicant.
 *
 * <p>This is the identifier every other bounded context sees. It is derived from
 * the applicant's identifying attributes with HMAC-SHA-256 under a secret pepper,
 * which gives three properties the platform depends on:
 *
 * <ul>
 *   <li><b>Stable.</b> The same person applying twice produces the same
 *       reference, so fraud and AML checks can correlate applications without
 *       any service outside this one holding personal data.
 *   <li><b>Irreversible.</b> An attacker who obtains the event stream, the audit
 *       store or a database backup cannot recover the applicant's identity from
 *       the reference.
 *   <li><b>Not brute-forceable.</b> A plain hash of a name and date of birth is
 *       trivially reversed by enumeration: the value space is tiny. The pepper is
 *       what makes that attack infeasible, so it lives in AWS Secrets Manager and
 *       never in configuration, an image or the repository.
 * </ul>
 *
 * <p>Rotating the pepper deliberately breaks correlation with previously issued
 * references. That is a business decision, not an operational one; see the
 * secret-rotation runbook.
 */
public record ApplicantReference(String value) {

    private static final String PREFIX = "APR-";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int REFERENCE_HEX_LENGTH = 16;

    public ApplicantReference {
        if (value == null || !value.startsWith(PREFIX) || value.length() != PREFIX.length() + REFERENCE_HEX_LENGTH) {
            throw new IllegalArgumentException("Not a valid applicant reference");
        }
    }

    /**
     * Derives the pseudonym for the given applicant.
     *
     * @param details the applicant's identifying attributes
     * @param pepper  secret keying material loaded from AWS Secrets Manager
     */
    public static ApplicantReference deriveFrom(ApplicantDetails details, byte[] pepper) {
        if (pepper == null || pepper.length < 32) {
            throw new IllegalArgumentException("Applicant reference pepper must be at least 32 bytes");
        }

        // Field values are length-prefixed before concatenation so that two
        // different applicants cannot produce the same input string by shifting a
        // character across a field boundary.
        String canonical = lengthPrefixed(details.familyName())
                + lengthPrefixed(details.givenName())
                + lengthPrefixed(details.dateOfBirth().toString())
                + lengthPrefixed(details.residenceCountry());

        byte[] mac = hmac(pepper, canonical.getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(mac).substring(0, REFERENCE_HEX_LENGTH);
        return new ApplicantReference(PREFIX + hex);
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // A JVM without HMAC-SHA-256 cannot run this service at all. There is
            // no meaningful recovery and no partial result worth returning.
            throw new IllegalStateException("HMAC-SHA-256 is not available in this JVM", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
