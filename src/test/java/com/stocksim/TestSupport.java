package com.stocksim;

import com.stocksim.model.Stock;
import com.stocksim.model.User;
import com.stocksim.service.AuthService;
import com.stocksim.service.MarketService;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.DBConnection;
import com.stocksim.util.DataSeeder;
import com.stocksim.util.SchemaMigrator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds a fresh test database once per run. It refuses to touch any other database. */
public final class TestSupport {
    public static final String PASSWORD = "password123";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static boolean ready;

    private TestSupport() {
    }

    public static synchronized void database() {
        if (ready) {
            return;
        }
        String url = ConfigLoader.get("db.url", "");
        if (!url.contains("/stocksim_test")) {
            throw new IllegalStateException("Tests only run against stocksim_test, not " + url);
        }
        try (Connection c = DriverManager.getConnection(url.replace("/stocksim_test", "/"),
                ConfigLoader.get("db.user", "root"), ConfigLoader.get("db.password", ""));
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS stocksim_test");
            st.execute("CREATE DATABASE stocksim_test CHARACTER SET utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL must be running to run the tests: " + e.getMessage(), e);
        }
        SchemaMigrator.migrate();
        DataSeeder.seedIfEmpty();
        ready = true;
    }

    public static User newUser() {
        database();
        String name = "t" + System.nanoTime() % 1_000_000_000 + "_" + COUNTER.incrementAndGet();
        return new AuthService().register("Test User", name, name + "@example.com", PASSWORD, PASSWORD);
    }

    public static Stock stock(String symbol) {
        database();
        return new MarketService().stocks().stream().filter(s -> s.getSymbol().equals(symbol)).findFirst()
                .orElseThrow();
    }

    /** First value of the first row, for checking the database directly. */
    public static Object scalar(String sql, Object... params) {
        try (Connection c = DBConnection.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long count(String table, int userId) {
        return (Long) scalar("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", userId);
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
