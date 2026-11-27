package com.stocksim.dao;

import com.stocksim.model.Holding;
import com.stocksim.model.Stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL for holdings. */
public class HoldingDAO extends BaseDAO {

    /** The stored part of a holding. */
    public record Position(int stockId, int quantity, BigDecimal costBasis) {
    }

    public List<Position> positions(Connection c, int userId) throws SQLException {
        return query(c, HoldingDAO::map, "SELECT * FROM holdings WHERE user_id = ? ORDER BY stock_id", userId);
    }

    /** Positions joined to stock details and valued at the supplied prices. */
    public List<Holding> holdings(Connection c, int userId, Map<Integer, Stock> stocks,
                                  Map<Integer, BigDecimal> prices) throws SQLException {
        return positions(c, userId).stream()
                .map(p -> new Holding(stocks.get(p.stockId()), p.quantity(), p.costBasis(), prices.get(p.stockId())))
                .toList();
    }

    public Optional<Position> lock(Connection c, int userId, int stockId) throws SQLException {
        return queryOne(c, HoldingDAO::map,
                "SELECT * FROM holdings WHERE user_id = ? AND stock_id = ? FOR UPDATE", userId, stockId);
    }

    /** Creates, replaces or (at zero quantity) removes a holding. */
    public void save(Connection c, int userId, int stockId, int quantity, BigDecimal costBasis) throws SQLException {
        if (quantity == 0) {
            update(c, "DELETE FROM holdings WHERE user_id = ? AND stock_id = ?", userId, stockId);
            return;
        }
        BigDecimal average = costBasis.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        update(c, "INSERT INTO holdings (user_id, stock_id, quantity, average_buy_price, cost_basis) "
                + "VALUES (?,?,?,?,?) AS n ON DUPLICATE KEY UPDATE quantity = n.quantity, "
                + "average_buy_price = n.average_buy_price, cost_basis = n.cost_basis",
                userId, stockId, quantity, average, costBasis);
    }

    public void deleteAll(Connection c, int userId) throws SQLException {
        update(c, "DELETE FROM holdings WHERE user_id = ?", userId);
    }

    private static Position map(java.sql.ResultSet rs) throws SQLException {
        return new Position(rs.getInt("stock_id"), rs.getInt("quantity"), rs.getBigDecimal("cost_basis"));
    }
}
