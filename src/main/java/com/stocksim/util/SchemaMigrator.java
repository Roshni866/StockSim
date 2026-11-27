package com.stocksim.util;

import com.stocksim.exception.DataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Applies the versioned scripts in db/migration exactly once each. Scripts only
 * add things. MySQL cannot roll back DDL, so "already exists" errors are
 * tolerated to make a half-applied script safe to run again.
 */
public final class SchemaMigrator {
    private static final Logger LOGGER = Logger.getLogger(SchemaMigrator.class.getName());
    private static final List<String> SCRIPTS = List.of("V1__baseline.sql", "V2__platform.sql");
    /** Duplicate column, duplicate key name, duplicate foreign key, table exists. */
    private static final Set<Integer> ALREADY_APPLIED = Set.of(1060, 1061, 1826, 1050);

    private SchemaMigrator() {
    }

    public static void migrate() {
        try (Connection conn = DBConnection.open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "script VARCHAR(100) PRIMARY KEY, "
                    + "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
            for (String script : SCRIPTS) {
                if (isApplied(conn, script)) {
                    continue;
                }
                for (String sql : statements(script)) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        if (!ALREADY_APPLIED.contains(e.getErrorCode())) {
                            throw new SQLException(script + " failed: " + e.getMessage(), e);
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version (script) VALUES (?)")) {
                    ps.setString(1, script);
                    ps.executeUpdate();
                }
                LOGGER.info("Applied database migration " + script);
            }
        } catch (SQLException | IOException e) {
            throw new DataAccessException("Database setup failed: " + e.getMessage(), e);
        }
    }

    private static boolean isApplied(Connection conn, String script) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_version WHERE script = ?")) {
            ps.setString(1, script);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Splits a script on semicolons that end a line, ignoring comment lines. */
    static List<String> statements(String script) throws IOException {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream("/db/migration/" + script)) {
            if (in == null) {
                throw new IOException("Migration script not found: " + script);
            }
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                current.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    result.add(current.substring(0, current.lastIndexOf(";")));
                    current.setLength(0);
                }
            }
            return result;
        }
    }
}
