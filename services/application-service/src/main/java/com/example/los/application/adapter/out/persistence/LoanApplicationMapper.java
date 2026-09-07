package com.example.los.application.adapter.out.persistence;

import java.util.Currency;

import org.springframework.stereotype.Component;

import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.Decision;
import com.example.los.application.domain.model.DecisionType;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.domain.model.LoanPurpose;
import com.example.los.application.domain.model.LoanRequest;
import com.example.los.application.domain.model.LoanTerm;
import com.example.los.application.domain.model.Money;

/**
 * Translates between the aggregate and its row.
 *
 * <p>Hand-written rather than generated. The mapping is small, it is read far
 * more often than it is changed, and a generator would hide the one thing that
 * matters here: which fields carry personal data.
 */
@Component
class LoanApplicationMapper {

    /**
     * Rebuilds the aggregate from a row.
     *
     * <p>Applicant details are reconstructed through the storage-side factory,
     * which skips the age and format validation applied at the API boundary.
     * Re-validating stored data would make an old row unreadable after a rule
     * changes, which is exactly when you most need to read it.
     */
    LoanApplication toDomain(LoanApplicationEntity entity) {
        ApplicantDetails applicant = ApplicantDetails.fromStorage(
                entity.getApplicantGivenName(),
                entity.getApplicantFamilyName(),
                entity.getApplicantEmail(),
                entity.getApplicantDateOfBirth(),
                entity.getApplicantResidenceCountry());

        Currency currency = Currency.getInstance(entity.getCurrency());
        LoanRequest request = new LoanRequest(
                new Money(entity.getAmountMinorUnits(), currency),
                LoanTerm.ofMonths(entity.getTermMonths()),
                LoanPurpose.valueOf(entity.getPurpose()),
                new Money(entity.getDeclaredAnnualIncomeMinorUnits(), currency),
                entity.getProductCode());

        Decision decision = entity.getDecisionType() == null
                ? null
                : new Decision(
                        DecisionType.valueOf(entity.getDecisionType()),
                        entity.getDecisionReasonCode(),
                        entity.getDecisionDecidedBy());

        return LoanApplication.reconstitute(
                new ApplicationId(entity.getId()),
                new ApplicantReference(entity.getApplicantReference()),
                applicant,
                request,
                ApplicationStatus.valueOf(entity.getStatus()),
                decision,
                entity.getCreatedAt(),
                entity.getSubmittedAt(),
                entity.getUpdatedAt(),
                entity.getAggregateVersion());
    }

    /** Copies the aggregate's current state onto a row, creating it if needed. */
    LoanApplicationEntity toEntity(LoanApplication application, LoanApplicationEntity existing) {
        LoanApplicationEntity entity =
                existing != null ? existing : new LoanApplicationEntity(application.id().value());

        entity.setApplicantReference(application.applicantReference().value());
        entity.setStatus(application.status().name());

        ApplicantDetails applicant = application.applicant();
        entity.setApplicantGivenName(applicant.givenName());
        entity.setApplicantFamilyName(applicant.familyName());
        entity.setApplicantEmail(applicant.emailAddress());
        entity.setApplicantDateOfBirth(applicant.dateOfBirth());
        entity.setApplicantResidenceCountry(applicant.residenceCountry());

        LoanRequest request = application.request();
        entity.setAmountMinorUnits(request.amount().minorUnits());
        entity.setCurrency(request.amount().currency().getCurrencyCode());
        entity.setTermMonths(request.term().months());
        entity.setPurpose(request.purpose().name());
        entity.setDeclaredAnnualIncomeMinorUnits(request.declaredAnnualIncome().minorUnits());
        entity.setProductCode(request.productCode());

        Decision decision = application.decision();
        entity.setDecisionType(decision == null ? null : decision.type().name());
        entity.setDecisionReasonCode(decision == null ? null : decision.reasonCode());
        entity.setDecisionDecidedBy(decision == null ? null : decision.decidedBy());

        entity.setAggregateVersion(application.version());
        entity.setCreatedAt(application.createdAt());
        entity.setSubmittedAt(application.submittedAt());
        entity.setUpdatedAt(application.updatedAt());

        return entity;
    }

    /** Snapshot of the requested terms for the immutable version history. */
    ApplicationVersionEntity toVersionEntity(LoanApplication application) {
        LoanRequest request = application.request();
        return new ApplicationVersionEntity(
                application.id().value(),
                application.version(),
                application.status().name(),
                request.amount().minorUnits(),
                request.amount().currency().getCurrencyCode(),
                request.term().months(),
                request.purpose().name(),
                request.declaredAnnualIncome().minorUnits(),
                request.productCode(),
                application.updatedAt());
    }
}
