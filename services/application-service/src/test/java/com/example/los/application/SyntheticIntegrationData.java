package com.example.los.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.LoanPurpose;
import com.example.los.application.domain.model.LoanRequest;
import com.example.los.application.domain.model.LoanTerm;
import com.example.los.application.domain.model.Money;
import com.example.los.events.vocabulary.DocumentStatuses;
import com.example.los.events.vocabulary.DocumentType;

/**
 * Synthetic data for integration tests.
 *
 * <p>Every value is invented. Names are placeholders, email addresses use the
 * RFC 2606 reserved {@code example.com} domain, and no value corresponds to a
 * real person, account or environment.
 */
final class SyntheticIntegrationData {

    private SyntheticIntegrationData() {}

    static ApplicantDetails applicant() {
        return applicantResidentIn("DE");
    }

    static ApplicantDetails applicantResidentIn(String countryCode) {
        return ApplicantDetails.of(
                "Test",
                "Applicant",
                "test.applicant@example.com",
                LocalDate.parse("1990-04-17"),
                countryCode,
                LocalDate.now());
    }

    static LoanRequest request() {
        return new LoanRequest(
                Money.of(1_250_000L, "EUR"),
                LoanTerm.ofMonths(48),
                LoanPurpose.HOME_IMPROVEMENT,
                Money.of(4_500_000L, "EUR"),
                "PL-STD-01");
    }

    /** A request that violates the loan-to-income rule and is therefore rejected at submission. */
    static LoanRequest unaffordableRequest() {
        return new LoanRequest(
                Money.of(7_000_000L, "EUR"),
                LoanTerm.ofMonths(48),
                LoanPurpose.DEBT_CONSOLIDATION,
                Money.of(1_000_000L, "EUR"),
                "PL-STD-01");
    }

    /**
     * Seeds the document-status projection so an application can be submitted.
     *
     * <p>Writes directly to the projection rather than publishing document events
     * and waiting for them. The projection's own consumer is tested separately;
     * making every submission test depend on Kafka delivery timing would make
     * them slow and flaky for no extra coverage.
     */
    static void markMandatoryDocumentsClean(JdbcTemplate jdbc, ApplicationId applicationId) {
        for (DocumentType type : DocumentType.values()) {
            if (!type.isMandatory()) {
                continue;
            }
            jdbc.update(
                    """
                    INSERT INTO application.document_status
                        (document_id, application_id, document_type, status, source_version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    applicationId.value(),
                    type.name(),
                    DocumentStatuses.CLEAN,
                    2L,
                    OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
}
