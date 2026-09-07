package com.example.los.workflow.domain.model;

/**
 * The risk-relevant figures the checks are run against.
 *
 * <p>Copied from the submission event, so this context never needs to call back
 * to the application service to assess an application. It holds no personal
 * data: the applicant is identified only by the pseudonymous reference on the
 * workflow instance.
 *
 * @param amountMinorUnits              requested principal in minor units
 * @param currency                      ISO 4217 currency code
 * @param termMonths                    requested repayment term
 * @param purpose                       declared loan purpose
 * @param declaredAnnualIncomeMinorUnits declared gross annual income in minor units
 * @param productCode                   internal product identifier
 */
public record AssessmentInputs(
        long amountMinorUnits,
        String currency,
        int termMonths,
        String purpose,
        long declaredAnnualIncomeMinorUnits,
        String productCode) {

    /** Requested principal as a proportion of declared annual income. */
    public double loanToIncomeRatio() {
        if (declaredAnnualIncomeMinorUnits == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) amountMinorUnits / declaredAnnualIncomeMinorUnits;
    }
}
