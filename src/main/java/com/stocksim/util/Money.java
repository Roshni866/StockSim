package com.stocksim.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Single place for money rounding rules, so every screen agrees to the paisa. */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** Rounds to 2 decimal places, half up. */
    public static BigDecimal of(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        return of(new BigDecimal(value));
    }

    public static BigDecimal of(double value) {
        return of(BigDecimal.valueOf(value));
    }

    public static BigDecimal times(BigDecimal price, long quantity) {
        return of(price.multiply(BigDecimal.valueOf(quantity)));
    }

    /** part / whole x 100, or zero when the whole is zero. */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return ZERO;
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    /** Percentage change from one value to another, or zero when the base is zero. */
    public static BigDecimal change(BigDecimal from, BigDecimal to) {
        return percent(to.subtract(from), from);
    }

    /** value x pct / 100. */
    public static BigDecimal percentOf(BigDecimal value, BigDecimal pct) {
        return of(value.multiply(pct).divide(HUNDRED));
    }
}
