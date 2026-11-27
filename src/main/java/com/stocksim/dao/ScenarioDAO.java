package com.stocksim.dao;

import com.stocksim.model.MarketScenario;
import com.stocksim.model.ScenarioReport.StockImpact;
import com.stocksim.model.ScenarioRule;
import com.stocksim.model.ScenarioRun;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for scenarios, their rules, runs and results. */
public class ScenarioDAO extends BaseDAO {

    private static final String RUN_SELECT = "SELECT r.*, s.title FROM scenario_runs r "
            + "JOIN market_scenarios s ON s.scenario_id = r.scenario_id WHERE r.user_id = ? ";

    public List<MarketScenario> findAll(Connection c) throws SQLException {
        List<MarketScenario> result = new ArrayList<>();
        for (int id : query(c, rs -> rs.getInt(1), "SELECT scenario_id FROM market_scenarios ORDER BY scenario_id")) {
            result.add(find(c, id).orElseThrow());
        }
        return result;
    }

    public Optional<MarketScenario> find(Connection c, int scenarioId) throws SQLException {
        List<ScenarioRule> rules = query(c, rs -> new ScenarioRule(
                        "MARKET".equals(rs.getString("scope")) ? null : rs.getString("sector"),
                        rs.getInt("from_day"), rs.getInt("to_day"), rs.getBigDecimal("daily_pct"),
                        rs.getString("headline")),
                "SELECT * FROM scenario_rules WHERE scenario_id = ? ORDER BY from_day, rule_id", scenarioId);
        return queryOne(c, rs -> new MarketScenario(rs.getInt("scenario_id"), rs.getString("code"),
                        rs.getString("title"), rs.getString("description"), rs.getInt("duration_days"),
                        rs.getString("severity"), rs.getLong("seed"), rs.getBigDecimal("noise_pct"), rules),
                "SELECT * FROM market_scenarios WHERE scenario_id = ?", scenarioId);
    }

    public int createRun(Connection c, int userId, MarketScenario s, int startDay, int endDay, BigDecimal value)
            throws SQLException {
        return insert(c, "INSERT INTO scenario_runs (user_id, scenario_id, seed, start_day, end_day, last_day, "
                + "start_portfolio_value, start_transaction_id) SELECT ?,?,?,?,?,?,?, "
                + "COALESCE(MAX(transaction_id), 0) FROM transactions WHERE user_id = ?",
                userId, s.id(), s.seed(), startDay, endDay, startDay, value, userId);
    }

    public void updateRun(Connection c, int runId, int lastDay, String status, BigDecimal endValue)
            throws SQLException {
        update(c, "UPDATE scenario_runs r SET last_day = ?, status = ?, end_portfolio_value = ?, "
                + "end_transaction_id = IF(? = 'ACTIVE', NULL, (SELECT COALESCE(MAX(t.transaction_id), 0) "
                + "FROM transactions t WHERE t.user_id = r.user_id)) WHERE run_id = ?",
                lastDay, status, endValue, status, runId);
    }

    /** Ids of the user's trades made while the run was active. */
    public List<Integer> tradeIds(Connection c, int userId, int runId) throws SQLException {
        return query(c, rs -> rs.getInt(1), "SELECT t.transaction_id FROM transactions t JOIN scenario_runs r "
                + "ON r.user_id = t.user_id WHERE r.run_id = ? AND r.user_id = ? "
                + "AND t.transaction_id > r.start_transaction_id "
                + "AND (r.end_transaction_id IS NULL OR t.transaction_id <= r.end_transaction_id)", runId, userId);
    }

    /** Finds a run only if it belongs to the user. */
    public Optional<ScenarioRun> findRun(Connection c, int userId, int runId) throws SQLException {
        return queryOne(c, ScenarioDAO::mapRun, RUN_SELECT + "AND r.run_id = ?", userId, runId);
    }

    public List<ScenarioRun> runs(Connection c, int userId) throws SQLException {
        return query(c, ScenarioDAO::mapRun, RUN_SELECT + "ORDER BY r.run_id DESC", userId);
    }

    /** rows: {runId, stockId, startPrice, endPrice} */
    public void saveResults(Connection c, List<Object[]> rows) throws SQLException {
        batch(c, "REPLACE INTO scenario_run_results (run_id, stock_id, start_price, end_price) VALUES (?,?,?,?)", rows);
    }

    /** Per-stock results, best performer first. */
    public List<StockImpact> results(Connection c, int runId) throws SQLException {
        return query(c, rs -> {
            BigDecimal start = rs.getBigDecimal("start_price");
            BigDecimal end = rs.getBigDecimal("end_price");
            return new StockImpact(rs.getString("symbol"), rs.getString("sector"), start, end,
                    Money.change(start, end));
        }, "SELECT r.*, s.symbol, s.sector FROM scenario_run_results r JOIN stocks s ON s.stock_id = r.stock_id "
                + "WHERE r.run_id = ? ORDER BY r.end_price / r.start_price DESC, s.symbol", runId);
    }

    public void deleteRuns(Connection c, int userId) throws SQLException {
        update(c, "DELETE FROM scenario_runs WHERE user_id = ?", userId);
    }

    private static ScenarioRun mapRun(java.sql.ResultSet rs) throws SQLException {
        return new ScenarioRun(rs.getInt("run_id"), rs.getInt("scenario_id"), rs.getString("title"),
                rs.getLong("seed"), rs.getInt("start_day"), rs.getInt("end_day"), rs.getInt("last_day"),
                rs.getString("status"), rs.getBigDecimal("start_portfolio_value"),
                rs.getBigDecimal("end_portfolio_value"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
