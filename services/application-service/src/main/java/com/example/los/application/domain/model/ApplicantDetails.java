package com.example.los.application.domain.model;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * The personal data of the applicant.
 *
 * <p><strong>This is the only type in the platform that holds personal data, and
 * it never leaves the application service.</strong> It is not published on an
 * event, not returned by the API in full, not written to a log and not copied
 * into an audit record. Everything outside this service identifies the applicant
 * by {@link ApplicantReference}, an irreversible pseudonym derived from these
 * values.
 *
 * <p>Deliberately <em>not</em> a record. A record generates a {@code toString()}
 * that prints every component, which means a single {@code log.info("{}", app)}
 * or a stack trace carrying the object would put a name, an email address and a
 * date of birth into a log aggregator. The redacting {@code toString()} below is
 * the primary defence; the logging pipeline's redaction filter is the second.
 *
 * <p>{@code equals} and {@code hashCode} are implemented over the full value so
 * the type still behaves as a value object.
 */
public final class ApplicantDetails {

    private static final int MINIMUM_AGE_YEARS = 18;
    private static final int MAXIMUM_AGE_YEARS = 100;

    private final String givenName;
    private final String familyName;
    private final String emailAddress;
    private final LocalDate dateOfBirth;
    private final String residenceCountry;

    private ApplicantDetails(
            String givenName,
            String familyName,
            String emailAddress,
            LocalDate dateOfBirth,
            String residenceCountry) {
        this.givenName = givenName;
        this.familyName = familyName;
        this.emailAddress = emailAddress;
        this.dateOfBirth = dateOfBirth;
        this.residenceCountry = residenceCountry;
    }

    /**
     * Validates and creates applicant details.
     *
     * <p>Validation failures carry field names but never the offending value: an
     * error message is one of the easiest ways for personal data to escape into a
     * log or an API response.
     */
    public static ApplicantDetails of(
            String givenName,
            String familyName,
            String emailAddress,
            LocalDate dateOfBirth,
            String residenceCountry,
            LocalDate today) {
        requireText(givenName, "givenName");
        requireText(familyName, "familyName");
        requireText(emailAddress, "emailAddress");
        Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null");
        requireText(residenceCountry, "residenceCountry");

        if (!emailAddress.contains("@") || emailAddress.startsWith("@") || emailAddress.endsWith("@")) {
            throw new IllegalArgumentException("emailAddress is not a valid address");
        }
        if (residenceCountry.length() != 2) {
            throw new IllegalArgumentException("residenceCountry must be an ISO 3166-1 alpha-2 code");
        }

        int age = Period.between(dateOfBirth, today).getYears();
        if (age < MINIMUM_AGE_YEARS) {
            throw new IllegalArgumentException("Applicant must be at least " + MINIMUM_AGE_YEARS + " years old");
        }
        if (age > MAXIMUM_AGE_YEARS) {
            throw new IllegalArgumentException("dateOfBirth is not plausible");
        }

        return new ApplicantDetails(
                givenName.strip(),
                familyName.strip(),
                emailAddress.strip().toLowerCase(java.util.Locale.ROOT),
                dateOfBirth,
                residenceCountry.toUpperCase(java.util.Locale.ROOT));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public String givenName() {
        return givenName;
    }

    public String familyName() {
        return familyName;
    }

    public String emailAddress() {
        return emailAddress;
    }

    public LocalDate dateOfBirth() {
        return dateOfBirth;
    }

    public String residenceCountry() {
        return residenceCountry;
    }

    public int ageOn(LocalDate date) {
        return Period.between(dateOfBirth, date).getYears();
    }

    /**
     * The only representation of the applicant that is safe to display: the
     * initial of each name. Used by the manual-review UI so a reviewer can tell
     * two applications apart without the API returning full personal data.
     */
    public String displayInitials() {
        return String.valueOf(Character.toUpperCase(givenName.charAt(0)))
                + Character.toUpperCase(familyName.charAt(0));
    }

    /** Country of residence is not personal data on its own and is safe to expose. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApplicantDetails that)) {
            return false;
        }
        return givenName.equals(that.givenName)
                && familyName.equals(that.familyName)
                && emailAddress.equals(that.emailAddress)
                && dateOfBirth.equals(that.dateOfBirth)
                && residenceCountry.equals(that.residenceCountry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(givenName, familyName, emailAddress, dateOfBirth, residenceCountry);
    }

    /**
     * Redacted on purpose. If this object reaches a log statement, an exception
     * message or a debugger transcript, nothing identifying is printed.
     */
    @Override
    public String toString() {
        return "ApplicantDetails[redacted]";
    }
}
