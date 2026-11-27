package com.stocksim.dao;

import com.stocksim.model.TradeType;
import com.stocksim.model.Transaction;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** SQL for the trade ledger. Only real (simulated-wallet) trades are stored here. */
public class TransactionDAO extends BaseDAO {

    private static final String SELECT =
            "SELECT t.*, s.symbol, s.company_name, h.recorded_at AS market_date FROM transactions t "
            + "JOIN stocks s ON s.stock_id = t.stock_id "
            + "LEFT JOIN stock_history h ON h.stock_id = t.stock_id AND h.simulation_day = t.simulation_day "
            + "WHERE t.user_id = ? ";

    public int insert(Connection c, int userId, int stockId, TradeType type, int quantity, BigDecimal price,
                      BigDecimal total, BigDecimal realized, int day) throws SQLException {
        return insert(c, "INSERT INTO transactions (user_id, stock_id, transaction_type, quantity, price, "
                + "total_amount, realized_pl, simulation_day) VALUES (?,?,?,?,?,?,?,?)",
                userId, stockId, type.name(), quantity, price, total, realized, day);
    }

    /** The user's ledger in execution order. */
    public List<Transaction> findByUser(Connection c, int userId) throws SQLException {
        return query(c, TransactionDAO::map, SELECT + "ORDER BY t.transaction_id", userId);
    }

    public Optional<Transaction> findById(Connection c, int userId, int transactionId) throws SQLException {
        return queryOne(c, TransactionDAO::map, SELECT + "AND t.transaction_id = ?", userId, transactionId);
    }

    public void updateRealized(Connection c, int transactionId, BigDecimal realized) throws SQLException {
        update(c, "UPDATE transactions SET realized_pl = ? WHERE transaction_id = ?", realized, transactionId);
    }

    public void deleteAll(Connection c, int userId) throws SQLException {
        update(c, "DELETE FROM transactions WHERE user_id = ?", userId);
    }

    private static Transaction map(java.sql.ResultSet rs) throws SQLException {
        java.sql.Date date = rs.getDate("market_date");
        return new Transaction(rs.getInt("transaction_id"), rs.getInt("stock_id"), rs.getString("symbol"),
                rs.getString("company_name"), TradeType.valueOf(rs.getString("transaction_type")),
                rs.getInt("quantity"), rs.getBigDecimal("price"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("realized_pl"), rs.getTimestamp("transaction_date").toLocalDateTime(),
                rs.getInt("simulation_day"), date == null ? null : date.toLocalDate());
    }
}
