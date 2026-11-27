package com.stocksim.service;

import com.stocksim.dao.AlertDAO;
import com.stocksim.dao.HoldingDAO;
import com.stocksim.dao.ScenarioDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.MarketEvent;
import com.stocksim.model.MarketScenario;
import com.stocksim.model.PriceAlert;
import com.stocksim.model.ScenarioRule;
import com.stocksim.model.ScenarioRun;
import com.stocksim.model.SimulationState;
import com.stocksim.model.Stock;
import com.stocksim.model.StockPrice;
import com.stocksim.simulation.SimulationEngine;
import com.stocksim.util.Database;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Moves one user's market forward in time. Prices, events, alerts, portfolio
 * snapshots, scenario progress and the clock are saved in a single transaction.
 */
public class SimulationService {
    private static final Logger LOGGER = Logger.getLogger(SimulationService.class.getName());
    public static final int DAY = 1;
    /** Trading days: the data has no weekends. */
    public static final int WEEK = 5;
    public static final int MONTH = 21;

    private final SimulationDAO simulation = new SimulationDAO();
    private final StockDAO stocks = new StockDAO();
    private final ScenarioDAO scenarios = new ScenarioDAO();
    private final AlertDAO alerts = new AlertDAO();
    private final PortfolioService portfolio = new PortfolioService();
    private final UserDAO users = new UserDAO();
    private final HoldingDAO holdings = new HoldingDAO();
    private final SimulationEngine engine = new SimulationEngine();

    /** What happened during an advance. completedRunId is set when a scenario finished. */
    public record AdvanceResult(int previousDay, int newDay, boolean reachedEnd, List<MarketEvent> events,
                                List<PriceAlert> triggeredAlerts, Integer completedRunId) {
        public int daysAdvanced() {
            return newDay - previousDay;
        }
    }

    public SimulationState state(int userId) {
        return Database.read(c -> simulation.find(c, userId, false))
                .orElseThrow(() -> new ValidationException("Simulation state not found."));
    }

    public AdvanceResult advance(int userId, int days) {
        if (days < 1 || days > 260) {
            throw new ValidationException("Advance between 1 and 260 days at a time.");
        }
        return Database.transaction(c -> advance(c, userId, days));
    }

    private AdvanceResult advance(Connection c, int userId, int days) throws SQLException {
        SimulationState state = simulation.find(c, userId, true)
                .orElseThrow(() -> new ValidationException("Simulation state not found."));
        int from = state.currentDay();
        int lastDay = stocks.lastDay(c);
        int target = Math.min(from + days, lastDay);
        if (target <= from) {
            return new AdvanceResult(from, from, true, List.of(), List.of(), null);
        }
        List<Stock> all = stocks.findAll(c);
        Map<Integer, List<StockPrice>> reference = stocks.allReferenceSeries(c, from, target);
        Map<Integer, BigDecimal> prices = new HashMap<>(portfolio.prices(c, userId, from));
        List<PriceAlert> pending = new ArrayList<>(
                alerts.findByUser(c, userId).stream().filter(a -> !a.triggered()).toList());
        ScenarioRun run = null;
        MarketScenario scenario = null;
        if (state.activeRunId() != null) {
            run = scenarios.findRun(c, userId, state.activeRunId()).orElse(null);
            if (run != null) {
                scenario = scenarios.find(c, run.scenarioId()).orElseThrow();
            }
        }
        List<MarketEvent> events = new ArrayList<>();
        List<PriceAlert> triggered = new ArrayList<>();
        Integer completed = null;

        for (int day = from + 1; day <= target; day++) {
            boolean inScenario = run != null && day <= run.endDay();
            Integer runId = inScenario ? run.runId() : null;
            List<MarketEvent> today = inScenario ? scenarioEvents(scenario, day - run.startDay(), day)
                    : simulation.scriptedEvents(c, day - state.startDay(), day);
            for (MarketEvent event : today) {
                simulation.insertEvent(c, userId, event, runId);
            }
            events.addAll(today);

            List<Object[]> rows = new ArrayList<>();
            for (Stock stock : all) {
                List<StockPrice> history = reference.get(stock.getId());
                BigDecimal previous = prices.get(stock.getId());
                BigDecimal price = inScenario
                        ? engine.scenarioDay(previous, scenario, run.seed(), day - run.startDay(), stock)
                        : engine.historicalDay(previous, history.get(day - 1 - from).price(),
                                history.get(day - from).price(), stock, today);
                prices.put(stock.getId(), price);
                rows.add(new Object[] {userId, stock.getId(), day, price, runId});
            }
            simulation.insertPrices(c, rows);

            for (var iterator = pending.iterator(); iterator.hasNext();) {
                PriceAlert alert = iterator.next();
                BigDecimal price = prices.get(alert.stockId());
                if (alert.isMetBy(price) && alerts.markTriggered(c, alert.id(), day, price)) {
                    triggered.add(new PriceAlert(alert.id(), alert.stockId(), alert.symbol(), alert.direction(),
                            alert.target(), true, day, reference.get(alert.stockId()).get(day - from).date(), price));
                    iterator.remove();
                }
            }

            if (inScenario) {
                if (day == run.endDay()) {
                    finishRun(c, userId, run, day, "COMPLETED");
                    completed = run.runId();
                    run = null;
                } else {
                    scenarios.updateRun(c, run.runId(), day, "ACTIVE", null);
                }
            }
            // Move the clock before valuing, so the snapshot uses this day's prices.
            simulation.save(c, new SimulationState(userId, state.startDay(), day, run == null ? null : run.runId()));
            portfolio.snapshot(c, userId, day);
        }
        LOGGER.info("User " + userId + " advanced from day " + from + " to " + target);
        return new AdvanceResult(from, target, target >= lastDay, events, triggered, completed);
    }

    /** Stores per-stock results and closes a run. */
    void finishRun(Connection c, int userId, ScenarioRun run, int day, String status) throws SQLException {
        Map<Integer, BigDecimal> start = portfolio.prices(c, userId, run.startDay());
        Map<Integer, BigDecimal> end = portfolio.prices(c, userId, day);
        scenarios.saveResults(c, start.keySet().stream()
                .map(id -> new Object[] {run.runId(), id, start.get(id), end.get(id)}).toList());
        BigDecimal value = users.findById(c, userId).orElseThrow().balance();
        for (HoldingDAO.Position position : holdings.positions(c, userId)) {
            value = value.add(Money.times(end.get(position.stockId()), position.quantity()));
        }
        scenarios.updateRun(c, run.runId(), day, status, value);
    }

    private static List<MarketEvent> scenarioEvents(MarketScenario scenario, int scenarioDay, int day) {
        return scenario.rules().stream().filter(r -> r.fromDay() == scenarioDay)
                .map((ScenarioRule r) -> new MarketEvent(day, r.headline(),
                        "Hypothetical event in the \"" + scenario.title() + "\" scenario, days " + r.fromDay()
                                + "-" + r.toDay() + ".", r.isMarketWide() ? "ALL" : r.sector(),
                        r.dailyPercent(), true))
                .toList();
    }
}
