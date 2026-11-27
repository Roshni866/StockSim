package com.stocksim.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC plumbing. Every statement is a PreparedStatement, and every method
 * takes the caller's Connection so several DAOs can join one transaction.
 */
public abstract class BaseDAO {

    /** Turns the current result row into an object. */
    @FunctionalInterface
    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    protected <T> List<T> query(Connection conn, RowMapper<T> mapper, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    protected <T> Optional<T> queryOne(Connection conn, RowMapper<T> mapper, String sql, Object... params)
            throws SQLException {
        return query(conn, mapper, sql, params).stream().findFirst();
    }

    protected int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Inserts one row and returns its generated key. */
    protected int insert(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned");
                }
                return keys.getInt(1);
            }
        }
    }

    protected void batch(Connection conn, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : rows) {
                bind(ps, row);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    protected static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
