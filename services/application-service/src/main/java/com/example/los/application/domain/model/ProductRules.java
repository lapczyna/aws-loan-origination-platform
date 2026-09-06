package com.example.los.application.domain.model;

import java.util.Currency;
import java.util.Set;

import com.example.los.application.domain.exception.ProductRuleViolationException;

/**
 * The eligibility rules a request must satisfy before any external check runs.
 *
 * <p>These are cheap, deterministic, local rules. Running them before the
 * workflow starts means an obviously ineligible application is rejected in
 * milliseconds instead of after four external calls, and it keeps the
 * expensive-check budget for applications that could actually be approved.
 *
 * <p>A violation is a business rejection. It is never retried, and the reason
 * code is safe to return to the caller.
 *
 * <p>In a production platform these limits would come from a product catalogue
 * rather than constants. They are inlined here because a configurable product
 * catalogue is a bounded context of its own and is out of scope; the shape of
 * the rule evaluation would not change.
 */
public final class ProductRules {

    private static final long MINIMUM_AMOUNT_MINOR_UNITS = 100_000L; // 1,000.00
    private static final long MAXIMUM_AMOUNT_MINOR_UNITS = 7_500_000L; // 75,000.00
    private static final double MAXIMUM_LOAN_TO_INCOME_RATIO = 5.0;

    /**
     * Countries the platform is licensed to lend in. Residence outside this set
     * is a licensing constraint, not a judgement about the applicant.
     */
    private static final Set<String> SUPPORTED_RESIDENCE_COUNTRIES = Set.of("DE", "FR", "NL", "ES", "IT", "PL", "IE");

    private ProductRules() {}

    /**
     * Evaluates every rule against the request.
     *
     * @throws ProductRuleViolationException on the first rule that fails
     */
    public static void validate(LoanRequest request, ApplicantDetails applicant) {
        if (request.amount().minorUnits() < MINIMUM_AMOUNT_MINOR_UNITS) {
            throw new ProductRuleViolationException(
                    ReasonCodes.AMOUNT_BELOW_PRODUCT_MINIMUM, "Requested amount is below the product minimum");
        }
        if (request.amount().minorUnits() > MAXIMUM_AMOUNT_MINOR_UNITS) {
            throw new ProductRuleViolationException(
                    ReasonCodes.AMOUNT_ABOVE_PRODUCT_MAXIMUM, "Requested amount is above the product maximum");
        }
        if (request.loanToIncomeRatio() > MAXIMUM_LOAN_TO_INCOME_RATIO) {
            throw new ProductRuleViolationException(
                    ReasonCodes.LOAN_TO_INCOME_TOO_HIGH, "Requested amount is too high for the declared income");
        }
        if (!SUPPORTED_RESIDENCE_COUNTRIES.contains(applicant.residenceCountry())) {
            throw new ProductRuleViolationException(
                    ReasonCodes.UNSUPPORTED_RESIDENCE_COUNTRY,
                    "The platform is not licensed to lend in the applicant's country of residence");
        }
    }

    public static Money minimumAmount(Currency currency) {
        return new Money(MINIMUM_AMOUNT_MINOR_UNITS, currency);
    }

    public static Money maximumAmount(Currency currency) {
        return new Money(MAXIMUM_AMOUNT_MINOR_UNITS, currency);
    }

    public static double maximumLoanToIncomeRatio() {
        return MAXIMUM_LOAN_TO_INCOME_RATIO;
    }

    public static Set<String> supportedResidenceCountries() {
        return SUPPORTED_RESIDENCE_COUNTRIES;
    }
}
