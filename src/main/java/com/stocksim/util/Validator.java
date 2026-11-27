package com.stocksim.util;

import com.stocksim.exception.ValidationException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Static validation helpers used by the service layer (never only by the UI). */
public final class Validator {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,50}$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    public static final int MIN_PASSWORD_LENGTH = 8;

    private Validator() {
    }

    public static boolean isNotEmpty(String value) {
        return value != null && !value.isBlank();
    }

    public static boolean isValidEmail(String email) {
        return isNotEmpty(email) && email.trim().length() <= 100 && EMAIL.matcher(email.trim()).matches();
    }

    public static boolean isValidUsername(String username) {
        return isNotEmpty(username) && USERNAME.matcher(username.trim()).matches();
    }

    /** 8 to 72 bytes (the BCrypt limit) with at least one letter and one digit. */
    public static boolean isValidPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH
                && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit);
    }

    /** Parses a whole number greater than zero, e.g. a share quantity. */
    public static int positiveInt(String text, String field) {
        try {
            int value = Integer.parseInt(text == null ? "" : text.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ValidationException(field + " must be a whole number greater than zero.");
    }

    /** Parses a positive amount of money; commas and the rupee sign are accepted. */
    public static BigDecimal positiveAmount(String text, String field) {
        try {
            String cleaned = text == null ? "" : text.replace(",", "").replace(Formats.RUPEE, "").trim();
            return requireAmount(new BigDecimal(cleaned), field);
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a number greater than zero.");
        }
    }

    public static BigDecimal requireAmount(BigDecimal value, String field) {
        if (value == null || Money.of(value).signum() <= 0) {
            throw new ValidationException(field + " must be a number greater than zero.");
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException(field + " is too large.");
        }
        return Money.of(value);
    }

    /** Parses any decimal number, e.g. a percentage that may be negative. */
    public static BigDecimal decimal(String text, String field) {
        try {
            return new BigDecimal((text == null ? "" : text.replace("%", "")).trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a number.");
        }
    }
}
