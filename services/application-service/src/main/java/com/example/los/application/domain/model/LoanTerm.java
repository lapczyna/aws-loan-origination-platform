package com.example.los.application.domain.model;

/**
 * Requested repayment term.
 *
 * <p>The bounds are a product rule, enforced in the type so that no code path can
 * construct an application with a term the platform does not offer.
 */
public record LoanTerm(int months) {

    public static final int MINIMUM_MONTHS = 6;
    public static final int MAXIMUM_MONTHS = 120;

    public LoanTerm {
        if (months < MINIMUM_MONTHS || months > MAXIMUM_MONTHS) {
            throw new IllegalArgumentException(
                    "Loan term must be between " + MINIMUM_MONTHS + " and " + MAXIMUM_MONTHS + " months");
        }
    }

    public static LoanTerm ofMonths(int months) {
        return new LoanTerm(months);
    }

    public int years() {
        return months / 12;
    }
}
