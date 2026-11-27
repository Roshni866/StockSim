package com.stocksim.dao;

import com.stocksim.model.Stock;
import com.stocksim.model.StockPrice;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;

/**
 * SQL for stocks and prices. A user's price on a day is their simulated price if
 * one was recorded, otherwise the untouched reference price in stock_history.
 */
public class StockDAO extends BaseDAO {

    private static final String USER_SERIES =
            "SELECT h.stock_id, h.simulation_day, h.recorded_at, COALESCE(p.price, h.price) AS price "
            + "FROM stock_history h LEFT JOIN simulation_prices p ON p.user_id = ? "
            + "AND p.stock_id = h.stock_id AND p.simulation_day = h.simulation_day "
            + "WHERE h.simulation_day BETWEEN ? AND ? ";

    public List<Stock> findAll(Connection c) throws SQLException {
        return query(c, StockDAO::mapStock, "SELECT * FROM stocks ORDER BY symbol");
    }

    public Optional<Stock> findById(Connection c, int stockId) throws SQLException {
        return queryOne(c, StockDAO::mapStock, "SELECT * FROM stocks WHERE stock_id = ?", stockId);
    }

    /** One stock's prices in this user's simulation, oldest first. */
    public List<StockPrice> userSeries(Connection c, int userId, int stockId, int fromDay, int toDay)
            throws SQLException {
        return query(c, StockDAO::mapPrice, USER_SERIES + "AND h.stock_id = ? ORDER BY h.simulation_day",
                userId, fromDay, toDay, stockId);
    }

    /** Every stock's prices in this user's simulation, keyed by stock id. */
    public Map<Integer, List<StockPrice>> allUserSeries(Connection c, int userId, int fromDay, int toDay)
            throws SQLException {
        Map<Integer, List<StockPrice>> result = new LinkedHashMap<>();
        for (StockPrice p : query(c, StockDAO::mapPrice,
                USER_SERIES + "ORDER BY h.stock_id, h.simulation_day", userId, fromDay, toDay)) {
            result.computeIfAbsent(p.stockId(), k -> new ArrayList<>()).add(p);
        }
        return result;
    }

    /** Untouched reference history, used by backtesting. */
    public Map<Integer, List<StockPrice>> allReferenceSeries(Connection c, int fromDay, int toDay)
            throws SQLException {
        Map<Integer, List<StockPrice>> result = new LinkedHashMap<>();
        for (StockPrice p : query(c, StockDAO::mapPrice,
                "SELECT * FROM stock_history WHERE simulation_day BETWEEN ? AND ? "
                + "ORDER BY stock_id, simulation_day", fromDay, toDay)) {
            result.computeIfAbsent(p.stockId(), k -> new ArrayList<>()).add(p);
        }
        return result;
    }

    public int lastDay(Connection c) throws SQLException {
        return queryOne(c, rs -> rs.getInt(1), "SELECT COALESCE(MAX(simulation_day), 0) FROM stock_history")
                .orElse(0);
    }

    public Map<Integer, java.time.LocalDate> dates(Connection c, int fromDay, int toDay) throws SQLException {
        Map<Integer, java.time.LocalDate> dates = new LinkedHashMap<>();
        query(c, rs -> dates.put(rs.getInt(1), rs.getDate(2).toLocalDate()),
                "SELECT DISTINCT simulation_day, recorded_at FROM stock_history "
                + "WHERE simulation_day BETWEEN ? AND ? ORDER BY simulation_day", fromDay, toDay);
        return dates;
    }

    public String dataSource(Connection c) throws SQLException {
        return queryOne(c, rs -> rs.getString(1),
                "SELECT meta_value FROM app_meta WHERE meta_key = 'data_source'").orElse("UNKNOWN");
    }

    private static Stock mapStock(java.sql.ResultSet rs) throws SQLException {
        return new Stock(rs.getInt("stock_id"), rs.getString("symbol"), rs.getString("company_name"),
                rs.getString("sector"), rs.getBigDecimal("beta"));
    }

    private static StockPrice mapPrice(java.sql.ResultSet rs) throws SQLException {
        return new StockPrice(rs.getInt("stock_id"), rs.getInt("simulation_day"),
                rs.getDate("recorded_at").toLocalDate(), rs.getBigDecimal("price"));
    }
}
