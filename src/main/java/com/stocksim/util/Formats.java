package com.stocksim.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared number/date formatting (Indian lakh/crore digit grouping). */
public final class Formats {
    public static final String RUPEE = "₹";
    public static final String NONE = "—";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH);

    private Formats() {
    }

    public static String money(BigDecimal amount) {
        if (amount == null) {
            return NONE;
        }
        return (amount.signum() < 0 ? "-" : "") + RUPEE + grouped(amount.abs());
    }

    public static String signedMoney(BigDecimal amount) {
        if (amount == null) {
            return NONE;
        }
        return (amount.signum() > 0 ? "+" : "") + money(amount);
    }

    public static String percent(BigDecimal value) {
        return value == null ? NONE : value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String signedPercent(BigDecimal value) {
        return value == null ? NONE : (value.signum() > 0 ? "+" : "") + percent(value);
    }

    public static String date(LocalDate value) {
        return value == null ? NONE : value.format(DATE);
    }

    public static String shortDate(LocalDate value) {
        return value == null ? NONE : value.format(SHORT_DATE);
    }

    public static String dateTime(LocalDateTime value) {
        return value == null ? NONE : value.format(DATE_TIME);
    }

    /** CSS style class matching the sign of a value. */
    public static String signClass(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "neutral";
        }
        return value.signum() > 0 ? "positive" : "negative";
    }

    /** Indian grouping: last three digits, then pairs. 100000 -> 1,00,000.00 */
    private static String grouped(BigDecimal nonNegative) {
        String text = nonNegative.setScale(2, RoundingMode.HALF_UP).toPlainString();
        int dot = text.indexOf('.');
        String whole = text.substring(0, dot);
        StringBuilder out = new StringBuilder(whole);
        for (int i = whole.length() - 3; i > 0; i -= 2) {
            out.insert(i, ',');
        }
        return out + text.substring(dot);
    }
}
