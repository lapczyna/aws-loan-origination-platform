package com.example.los.application.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

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
        Long amountMinorUnits,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code")
        String currency,

        @NotNull(message = "termMonths is required")
        @Min(value = 1, message = "termMonths must be positive")
        Integer termMonths,

        @NotBlank(message = "purpose is required")
        String purpose,

        @NotNull(message = "declaredAnnualIncomeMinorUnits is required")
        @PositiveOrZero(message = "declaredAnnualIncomeMinorUnits must not be negative")
        Long declaredAnnualIncomeMinorUnits,

        @NotBlank(message = "productCode is required")
        String productCode) {}
