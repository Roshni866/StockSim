package com.stocksim.util;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * BCrypt password hashing. Accounts created by the earlier version used SHA-256;
 * those are still verified here and re-hashed with BCrypt at the next login.
 */
public final class PasswordUtil {
    private static final int COST = 11;

    private PasswordUtil() {
    }

    public static String hash(String plainPassword) {
        return BCrypt.withDefaults().hashToString(COST, plainPassword.toCharArray());
    }

    public static boolean matches(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return false;
        }
        if (isLegacy(storedHash)) {
            return MessageDigest.isEqual(sha256(plainPassword).getBytes(StandardCharsets.UTF_8),
                    storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        return BCrypt.verifyer().verify(plainPassword.toCharArray(), storedHash).verified;
    }

    public static boolean isLegacy(String storedHash) {
        return storedHash.matches("[0-9a-fA-F]{64}");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
