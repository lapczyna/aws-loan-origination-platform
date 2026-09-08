package com.example.los.application.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requested loan terms.
 *
 * <p>Amounts are integer minor units on the wire as well as in the model. A JSON
 * number for money is a decimal literal that different languages parse into
 * different floating-point values; an integer count of cents is unambiguous in
 * every client.
 */
public record LoanRequestPayload(
        @NotNull(message = "amountMinorUnits is required")
        @Positive(message = "amountMinorUnits must be positive")
        @Schema(
                        description = "The requested principal in the currency's MINOR units - cents, "
                                + "not euros. Money is never a floating-point number here.",
                        example = "1500000")
                Long amountMinorUnits,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code")
        String currency,

        @NotNull(message = "termMonths is required")
        @Min(value = 1, message = "termMonths must be positive")
        Integer termMonths,

        @NotBlank(message = "purpose is required")
        @Schema(
                        allowableValues = {
                            "HOME_IMPROVEMENT",
                            "DEBT_CONSOLIDATION",
                            "VEHICLE",
                            "EDUCATION",
                            "MEDICAL",
                            "BUSINESS",
                            "OTHER"
                        })
                String purpose,

        @NotNull(message = "declaredAnnualIncomeMinorUnits is required")
        @PositiveOrZero(message = "declaredAnnualIncomeMinorUnits must not be negative")
        @Schema(description = "Declared gross annual income, in minor units.", example = "6000000")
                Long declaredAnnualIncomeMinorUnits,

        @NotBlank(message = "productCode is required")
        @Schema(description = "Internal product identifier.", example = "PERSONAL-LOAN-STANDARD")
                String productCode) {}
