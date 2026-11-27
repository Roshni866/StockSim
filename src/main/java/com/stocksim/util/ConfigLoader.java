package com.stocksim.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Layered configuration: bundled defaults, then user/local files, then
 * environment variables, then JVM system properties. Keeps passwords out of Git.
 */
public final class ConfigLoader {
    private static final Properties PROPS = new Properties();
    private static final Map<String, String> ENV_KEYS = Map.of(
            "STOCKSIM_DB_URL", "db.url",
            "STOCKSIM_DB_USER", "db.user",
            "STOCKSIM_DB_PASSWORD", "db.password",
            "STOCKSIM_CSV_PATH", "csv.path");

    static {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/application.properties")) {
            if (in == null) {
                throw new IOException("application.properties not found on the classpath");
            }
            PROPS.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        overlay(Path.of(System.getProperty("user.home"), ".stocksim", "stocksim.properties"));
        overlay(Path.of("stocksim.local.properties"));
        ENV_KEYS.forEach((env, key) -> {
            String value = System.getenv(env);
            if (value != null) {
                PROPS.setProperty(key, value);
            }
        });
    }

    private ConfigLoader() {
    }

    private static void overlay(Path file) {
        if (!Files.isReadable(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PROPS.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read configuration file " + file, e);
        }
    }

    public static String get(String key, String defaultValue) {
        return System.getProperty(key, PROPS.getProperty(key, defaultValue));
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static java.math.BigDecimal getDecimal(String key, String defaultValue) {
        try {
            return new java.math.BigDecimal(get(key, defaultValue).trim());
        } catch (NumberFormatException e) {
            return new java.math.BigDecimal(defaultValue);
        }
    }
}
