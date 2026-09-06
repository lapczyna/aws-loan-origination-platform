package com.example.los.application.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount held in minor units.
 *
 * <p>Amounts are integers of the currency's smallest unit, never doubles and
 * never {@code BigDecimal} arithmetic spread across the codebase. Money that
 * rounds differently in two services is a class of defect this type exists to
 * prevent.
 *
 * @param minorUnits amount in the smallest unit of the currency, for example cents
 * @param currency   ISO 4217 currency
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Monetary amounts must not be negative");
        }
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public BigDecimal toMajorUnits() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(currency.getDefaultFractionDigits());
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot compare amounts in different currencies: " + currency + " and " + other.currency);
        }
    }

    @Override
    public String toString() {
        return toMajorUnits() + " " + currency.getCurrencyCode();
    }
}
