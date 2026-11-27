package com.stocksim.dao;

import com.stocksim.model.MarketEvent;
import com.stocksim.model.PortfolioSnapshot;
import com.stocksim.model.SimulationState;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** SQL for a user's simulation clock, simulated prices, events and snapshots. */
public class SimulationDAO extends BaseDAO {

    public Optional<SimulationState> find(Connection c, int userId, boolean forUpdate) throws SQLException {
        return queryOne(c, rs -> new SimulationState(rs.getInt("user_id"), rs.getInt("start_day"),
                        rs.getInt("current_day"), nullableInt(rs, "active_run_id")),
                "SELECT * FROM simulation_state WHERE user_id = ?" + (forUpdate ? " FOR UPDATE" : ""), userId);
    }

    public void create(Connection c, int userId, int startDay) throws SQLException {
        update(c, "INSERT INTO simulation_state (user_id, start_day, current_day) VALUES (?,?,?)",
                userId, startDay, startDay);
        update(c, "UPDATE users SET sim_day = ? WHERE user_id = ?", startDay, userId);
    }

    public void save(Connection c, SimulationState s) throws SQLException {
        update(c, "UPDATE simulation_state SET current_day = ?, active_run_id = ? WHERE user_id = ?",
                s.currentDay(), s.activeRunId(), s.userId());
        update(c, "UPDATE users SET sim_day = ? WHERE user_id = ?", s.currentDay(), s.userId());
    }

    /** rows: {userId, stockId, day, price, scenarioRunId} */
    public void insertPrices(Connection c, List<Object[]> rows) throws SQLException {
        batch(c, "REPLACE INTO simulation_prices (user_id, stock_id, simulation_day, price, scenario_run_id) "
                + "VALUES (?,?,?,?,?)", rows);
    }

    public void insertEvent(Connection c, int userId, MarketEvent e, Integer runId) throws SQLException {
        update(c, "INSERT INTO simulation_events (user_id, simulation_day, title, description, sector, impact_pct, "
                + "source, scenario_run_id) VALUES (?,?,?,?,?,?,?,?)", userId, e.day(), e.title(), e.description(),
                e.sector(), e.impactPercent(), e.fromScenario() ? "SCENARIO" : "SCRIPTED", runId);
    }

    /** Scripted events for one day counted from the simulation start. */
    public List<MarketEvent> scriptedEvents(Connection c, int offset, int simulationDay) throws SQLException {
        return query(c, rs -> new MarketEvent(simulationDay, rs.getString("title"), rs.getString("description"),
                        rs.getString("sector"), rs.getBigDecimal("price_impact_pct"), false),
                "SELECT * FROM market_events WHERE simulation_day = ? ORDER BY event_id", offset);
    }

    /** The user's most recent events, newest first. */
    public List<MarketEvent> recentEvents(Connection c, int userId, int limit) throws SQLException {
        return query(c, rs -> new MarketEvent(rs.getInt("simulation_day"), rs.getString("title"),
                        rs.getString("description"), rs.getString("sector"), rs.getBigDecimal("impact_pct"),
                        "SCENARIO".equals(rs.getString("source"))),
                "SELECT * FROM simulation_events WHERE user_id = ? "
                + "ORDER BY simulation_day DESC, sim_event_id DESC LIMIT ?", userId, limit);
    }

    public void saveSnapshot(Connection c, int userId, int day, BigDecimal cash, BigDecimal holdings)
            throws SQLException {
        update(c, "REPLACE INTO portfolio_snapshots (user_id, simulation_day, cash, holdings_value, total_value) "
                + "VALUES (?,?,?,?,?)", userId, day, cash, holdings, cash.add(holdings));
    }

    public List<PortfolioSnapshot> snapshots(Connection c, int userId) throws SQLException {
        return query(c, rs -> new PortfolioSnapshot(rs.getInt("simulation_day"),
                        rs.getDate("market_date").toLocalDate(), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("holdings_value"), rs.getBigDecimal("total_value")),
                "SELECT p.*, (SELECT MIN(h.recorded_at) FROM stock_history h "
                + "WHERE h.simulation_day = p.simulation_day) AS market_date "
                + "FROM portfolio_snapshots p WHERE p.user_id = ? ORDER BY p.simulation_day", userId);
    }

    /** Removes everything the simulation generated for a user. */
    public void clear(Connection c, int userId) throws SQLException {
        update(c, "DELETE FROM simulation_state WHERE user_id = ?", userId);
        update(c, "DELETE FROM simulation_prices WHERE user_id = ?", userId);
        update(c, "DELETE FROM simulation_events WHERE user_id = ?", userId);
        update(c, "DELETE FROM portfolio_snapshots WHERE user_id = ?", userId);
    }
}
