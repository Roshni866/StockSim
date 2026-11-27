package com.stocksim.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/** SQL for the per-user watchlist. */
public class WatchlistDAO extends BaseDAO {

    public Set<Integer> stockIds(Connection c, int userId) throws SQLException {
        return new LinkedHashSet<>(query(c, rs -> rs.getInt(1),
                "SELECT stock_id FROM watchlist WHERE user_id = ? ORDER BY added_at, stock_id", userId));
    }

    public void add(Connection c, int userId, int stockId) throws SQLException {
        update(c, "INSERT IGNORE INTO watchlist (user_id, stock_id) VALUES (?,?)", userId, stockId);
    }

    public void remove(Connection c, int userId, int stockId) throws SQLException {
        update(c, "DELETE FROM watchlist WHERE user_id = ? AND stock_id = ?", userId, stockId);
    }
}
