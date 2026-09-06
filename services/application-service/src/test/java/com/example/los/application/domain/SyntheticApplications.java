package com.example.los.application.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.LoanPurpose;
import com.example.los.application.domain.model.LoanRequest;
import com.example.los.application.domain.model.LoanTerm;
import com.example.los.application.domain.model.Money;
import com.example.los.events.vocabulary.DocumentType;

/**
 * Builders for synthetic test data.
 *
 * <p>Every value here is invented. The names are placeholder names, the email
 * addresses use the RFC 2606 reserved {@code example.com} domain, and the pepper
 * is a fixed test-only byte array. Nothing in this file corresponds to a real
 * person, a real credential or a real environment.
 */
public final class SyntheticApplications {

    /**
     * A fixed, non-secret pepper used only by tests, so derived references are
     * reproducible across runs. The real pepper comes from AWS Secrets Manager
     * and never appears in source.
     */
    public static final byte[] TEST_PEPPER = "test-only-pepper-not-a-secret-0123456789".getBytes();

    public static final Instant NOW = Instant.parse("2026-01-15T09:30:00Z");
    public static final LocalDate TODAY = LocalDate.parse("2026-01-15");

    private SyntheticApplications() {}

    public static ApplicantDetails applicant() {
        return applicantResidentIn("DE");
    }

    public static ApplicantDetails applicantResidentIn(String countryCode) {
        return ApplicantDetails.of(
                "Alex", "Placeholder", "alex.placeholder@example.com", LocalDate.parse("1990-04-17"), countryCode, TODAY);
    }

    public static ApplicantReference referenceFor(ApplicantDetails details) {
        return ApplicantReference.deriveFrom(details, TEST_PEPPER);
    }

    public static LoanRequest request() {
        return requestOf(1_250_000L, 4_500_000L, 48);
    }

    public static LoanRequest requestOf(long amountMinorUnits, long annualIncomeMinorUnits, int termMonths) {
        return new LoanRequest(
                Money.of(amountMinorUnits, "EUR"),
                LoanTerm.ofMonths(termMonths),
                LoanPurpose.HOME_IMPROVEMENT,
                Money.of(annualIncomeMinorUnits, "EUR"),
                "PL-STD-01");
    }

    /** A draft in its initial state. */
    public static LoanApplication draft() {
        ApplicantDetails applicant = applicant();
        return LoanApplication.createDraft(
                ApplicationId.newId(), applicant, referenceFor(applicant), request(), NOW);
    }

    /** An application that has been submitted with all mandatory documents clean. */
    public static LoanApplication submitted() {
        LoanApplication application = draft();
        application.submit(allMandatoryDocumentsClean(), NOW);
        return application;
    }

    /** An application that has reached the external-check stage. */
    public static LoanApplication inChecks() {
        LoanApplication application = submitted();
        application.beginValidation(NOW);
        application.beginChecks(NOW);
        return application;
    }

    public static Set<DocumentType> allMandatoryDocumentsClean() {
        Set<DocumentType> clean = EnumSet.noneOf(DocumentType.class);
        for (DocumentType type : DocumentType.values()) {
            if (type.isMandatory()) {
                clean.add(type);
            }
        }
        return clean;
    }
}
