package com.stocksim.util;

import com.stocksim.exception.DataAccessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs a unit of work on one connection, with commit/rollback. */
public final class Database {
    private static final Logger LOGGER = Logger.getLogger(Database.class.getName());

    /** A piece of database work that may fail with SQL or domain exceptions. */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run(Connection conn) throws SQLException, E;
    }

    private Database() {
    }

    /** All-or-nothing: commits when the work returns, rolls back on any failure. */
    public static <T, E extends Exception> T transaction(Work<T, E> work) throws E {
        try (Connection conn = DBConnection.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = work.run(conn);
                conn.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    conn.rollback();
                    LOGGER.warning("Database transaction rolled back.");
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database transaction failed", e);
            throw new DataAccessException("The database operation failed: " + e.getMessage(), e);
        }
    }

    /** Read-only convenience: one auto-commit connection. */
    public static <T, E extends Exception> T read(Work<T, E> work) throws E {
        try (Connection conn = DBConnection.open()) {
            return work.run(conn);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database read failed", e);
            throw new DataAccessException("Could not read from the database: " + e.getMessage(), e);
        }
    }
}
