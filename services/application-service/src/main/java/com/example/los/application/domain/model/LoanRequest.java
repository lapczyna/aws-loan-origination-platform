package com.example.los.application.domain.model;

import java.util.Objects;

/**
 * What the applicant is asking for.
 *
 * <p>Grouped into one value object so the aggregate has a single field to
 * replace when a draft is edited, and so product rules have one thing to
 * validate rather than five loose parameters.
 *
 * @param amount                requested principal
 * @param term                  requested repayment term
 * @param purpose               declared purpose
 * @param declaredAnnualIncome  gross annual income as declared by the applicant
 * @param productCode           internal product identifier
 */
public record LoanRequest(
        Money amount, LoanTerm term, LoanPurpose purpose, Money declaredAnnualIncome, String productCode) {

    public LoanRequest {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(term, "term must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(declaredAnnualIncome, "declaredAnnualIncome must not be null");
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode must not be blank");
        }
        if (!amount.currency().equals(declaredAnnualIncome.currency())) {
            throw new IllegalArgumentException("Requested amount and declared income must use the same currency");
        }
    }

    /**
     * Requested principal as a proportion of declared annual income.
     *
     * <p>A crude affordability signal used by the product rules before any
     * external credit assessment runs.
     */
    public double loanToIncomeRatio() {
        if (declaredAnnualIncome.minorUnits() == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) amount.minorUnits() / declaredAnnualIncome.minorUnits();
    }
}
