package com.stocksim.dao;

import com.stocksim.model.PriceAlert;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** SQL for price alerts. `condition` is a reserved word, hence the backticks. */
public class AlertDAO extends BaseDAO {

    public int insert(Connection c, int userId, int stockId, PriceAlert.Direction direction, BigDecimal target)
            throws SQLException {
        return insert(c, "INSERT INTO alerts (user_id, stock_id, `condition`, target_price) VALUES (?,?,?,?)",
                userId, stockId, direction.name(), target);
    }

    public List<PriceAlert> findByUser(Connection c, int userId) throws SQLException {
        return query(c, rs -> {
            java.sql.Date date = rs.getDate("triggered_date");
            return new PriceAlert(rs.getInt("alert_id"), rs.getInt("stock_id"), rs.getString("symbol"),
                    PriceAlert.Direction.valueOf(rs.getString("condition")), rs.getBigDecimal("target_price"),
                    rs.getBoolean("triggered"), nullableInt(rs, "triggered_day"),
                    date == null ? null : date.toLocalDate(), rs.getBigDecimal("triggered_price"));
        }, "SELECT a.*, s.symbol, h.recorded_at AS triggered_date FROM alerts a "
                + "JOIN stocks s ON s.stock_id = a.stock_id LEFT JOIN stock_history h "
                + "ON h.stock_id = a.stock_id AND h.simulation_day = a.triggered_day "
                + "WHERE a.user_id = ? ORDER BY a.alert_id DESC", userId);
    }

    /** Marks an alert triggered; the guard on triggered = FALSE makes it fire only once. */
    public boolean markTriggered(Connection c, int alertId, int day, BigDecimal price) throws SQLException {
        return update(c, "UPDATE alerts SET triggered = TRUE, triggered_day = ?, triggered_price = ? "
                + "WHERE alert_id = ? AND triggered = FALSE", day, price, alertId) == 1;
    }

    public boolean delete(Connection c, int userId, int alertId) throws SQLException {
        return update(c, "DELETE FROM alerts WHERE alert_id = ? AND user_id = ?", alertId, userId) == 1;
    }

    /** Re-arms alerts after a simulation reset. */
    public void rearmAll(Connection c, int userId) throws SQLException {
        update(c, "UPDATE alerts SET triggered = FALSE, triggered_day = NULL, triggered_price = NULL "
                + "WHERE user_id = ?", userId);
    }
}
