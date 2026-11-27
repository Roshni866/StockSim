package com.stocksim.service;

import com.stocksim.dao.ScenarioDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.TransactionDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.MarketScenario;
import com.stocksim.model.PortfolioSnapshot;
import com.stocksim.model.ScenarioReport;
import com.stocksim.model.ScenarioReport.StockImpact;
import com.stocksim.model.ScenarioRun;
import com.stocksim.model.SimulationState;
import com.stocksim.model.Stock;
import com.stocksim.model.Transaction;
import com.stocksim.analytics.MarketIndex;
import com.stocksim.util.Database;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Starting, cancelling and reporting on hypothetical market scenarios. */
public class ScenarioService {
    private final ScenarioDAO scenarios = new ScenarioDAO();
    private final SimulationDAO simulation = new SimulationDAO();
    private final StockDAO stocks = new StockDAO();
    private final TransactionDAO transactions = new TransactionDAO();
    private final PortfolioService portfolio = new PortfolioService();
    private final SimulationService time = new SimulationService();

    public List<MarketScenario> scenarios() {
        return Database.read(scenarios::findAll);
    }

    public List<ScenarioRun> runs(int userId) {
        return Database.read(c -> scenarios.runs(c, userId));
    }

    public Optional<ScenarioRun> activeRun(int userId) {
        return Database.read(c -> {
            Integer id = simulation.find(c, userId, false).map(SimulationState::activeRunId).orElse(null);
            return id == null ? Optional.<ScenarioRun>empty() : scenarios.findRun(c, userId, id);
        });
    }

    /** Activates a scenario from today. Only one can run at a time. */
    public ScenarioRun start(int userId, int scenarioId) {
        return Database.transaction(c -> {
            SimulationState state = simulation.find(c, userId, true)
                    .orElseThrow(() -> new ValidationException("Simulation state not found."));
            if (state.activeRunId() != null) {
                throw new ValidationException("A scenario is already running. Finish or cancel it first.");
            }
            MarketScenario scenario = scenarios.find(c, scenarioId)
                    .orElseThrow(() -> new ValidationException("Choose a scenario."));
            int end = state.currentDay() + scenario.durationDays();
            if (end > stocks.lastDay(c)) {
                throw new ValidationException("Only " + (stocks.lastDay(c) - state.currentDay())
                        + " simulation days remain; this scenario needs " + scenario.durationDays() + ".");
            }
            int runId = scenarios.createRun(c, userId, scenario, state.currentDay(), end,
                    portfolio.load(c, userId).totalValue());
            simulation.save(c, new SimulationState(userId, state.startDay(), state.currentDay(), runId));
            return scenarios.findRun(c, userId, runId).orElseThrow();
        });
    }

    /** Stops the active scenario. Prices already simulated stay as they are. */
    public void cancel(int userId) {
        Database.transaction(c -> {
            SimulationState state = simulation.find(c, userId, true).orElseThrow();
            if (state.activeRunId() == null) {
                throw new ValidationException("No scenario is running.");
            }
            ScenarioRun run = scenarios.findRun(c, userId, state.activeRunId()).orElseThrow();
            time.finishRun(c, userId, run, state.currentDay(), "CANCELLED");
            simulation.save(c, new SimulationState(userId, state.startDay(), state.currentDay(), null));
            return null;
        });
    }

    /** Advances through all remaining days of the active scenario. */
    public SimulationService.AdvanceResult runToEnd(int userId) {
        ScenarioRun run = activeRun(userId).orElseThrow(() -> new ValidationException("No scenario is running."));
        return time.advance(userId, run.endDay() - time.state(userId).currentDay());
    }

    /** Results of a run owned by this user; for an active run, results so far. */
    public ScenarioReport report(int userId, int runId) {
        return Database.read(c -> {
            ScenarioRun run = scenarios.findRun(c, userId, runId)
                    .orElseThrow(() -> new ValidationException("Scenario run not found."));
            List<StockImpact> impacts = run.isActive() ? liveImpacts(c, userId, run) : scenarios.results(c, runId);
            BigDecimal end = run.endValue() != null ? run.endValue()
                    : simulation.snapshots(c, userId).stream().filter(s -> s.day() == run.lastDay())
                            .map(PortfolioSnapshot::totalValue).findFirst().orElse(run.startValue());
            BigDecimal ratios = BigDecimal.ZERO;
            Map<String, List<BigDecimal>> sectorChanges = new LinkedHashMap<>();
            for (StockImpact s : impacts) {
                ratios = ratios.add(s.endPrice().divide(s.startPrice(), 10, RoundingMode.HALF_UP));
                sectorChanges.computeIfAbsent(s.sector(), k -> new ArrayList<>()).add(s.changePercent());
            }
            BigDecimal startIndex = Money.of(MarketIndex.BASE);
            BigDecimal endIndex = Money.of(ratios.multiply(startIndex)
                    .divide(BigDecimal.valueOf(impacts.size()), 6, RoundingMode.HALF_UP));
            BigDecimal market = Money.change(startIndex, endIndex);
            Map<String, BigDecimal> sectors = new LinkedHashMap<>();
            sectorChanges.forEach((sector, list) -> sectors.put(sector,
                    list.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP)));
            Set<Integer> during = new HashSet<>(scenarios.tradeIds(c, userId, runId));
            List<Transaction> trades = transactions.findByUser(c, userId).stream()
                    .filter(t -> during.contains(t.id())).toList();
            String summary = String.format("Hypothetical \"%s\" scenario, %d of %d days (%s). The simulated index "
                            + "moved %s while your portfolio went from %s to %s. %s did best (%s) and %s did worst "
                            + "(%s). You made %d trade(s). These are invented market movements, not historical events.",
                    run.title(), run.lastDay() - run.startDay(), run.endDay() - run.startDay(),
                    run.status().toLowerCase(), Formats.signedPercent(market), Formats.money(run.startValue()),
                    Formats.money(end), impacts.get(0).symbol(), Formats.signedPercent(impacts.get(0).changePercent()),
                    impacts.get(impacts.size() - 1).symbol(),
                    Formats.signedPercent(impacts.get(impacts.size() - 1).changePercent()), trades.size());
            return new ScenarioReport(run, startIndex, endIndex, market, run.startValue(), end, impacts, sectors,
                    trades, summary);
        });
    }

    private List<StockImpact> liveImpacts(Connection c, int userId, ScenarioRun run) throws SQLException {
        Map<Integer, Stock> byId = stocks.findAll(c).stream()
                .collect(Collectors.toMap(Stock::getId, Function.identity()));
        Map<Integer, BigDecimal> start = portfolio.prices(c, userId, run.startDay());
        Map<Integer, BigDecimal> now = portfolio.prices(c, userId, run.lastDay());
        return start.keySet().stream()
                .map(id -> new StockImpact(byId.get(id).getSymbol(), byId.get(id).getSector(), start.get(id),
                        now.get(id), Money.change(start.get(id), now.get(id))))
                .sorted(Comparator.comparing(StockImpact::changePercent).reversed()
                        .thenComparing(StockImpact::symbol))
                .toList();
    }
}
