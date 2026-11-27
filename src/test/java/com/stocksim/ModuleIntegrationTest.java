package com.stocksim;

import com.stocksim.analytics.WhatIfEngine;
import com.stocksim.backtesting.BacktestConfig;
import com.stocksim.backtesting.StrategyType;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.*;
import com.stocksim.service.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.stocksim.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ModuleIntegrationTest {
    private final TradingService trading = new TradingService();
    private final PortfolioService portfolios = new PortfolioService();
    private final SimulationService time = new SimulationService();

    @BeforeAll
    static void setUp() {
        database();
    }

    /** Everything a hypothetical module must never change. */
    private String realState(User u) {
        return new AuthService().refresh(u.userId()).balance() + "|"
                + portfolios.getPortfolio(u.userId()).holdings().stream()
                        .map(h -> h.getSymbol() + h.getQuantity() + h.getCostBasis()).toList() + "|"
                + portfolios.transactions(u.userId()) + "|" + time.state(u.userId()) + "|"
                + new MarketService().quotes(u.userId()).stream().map(Quote::price).toList() + "|"
                + portfolios.history(u.userId());
    }

    private User investor() throws Exception {
        User u = newUser();
        trading.buy(u.userId(), stock("TCS").getId(), 15);
        trading.buy(u.userId(), stock("HDFCBANK").getId(), 10);
        trading.buy(u.userId(), stock("ITC").getId(), 8);
        time.advance(u.userId(), 21);
        return u;
    }

    private BacktestConfig backtest(StrategyType type, int to) {
        return new BacktestConfig(stock("TCS").getId(), type, 100, to, bd("100000"), bd("0.1"), 5, 20, 10,
                bd("3"), bd("-3"));
    }

    @Test
    void whatIfNeverTouchesTheRealPortfolio() throws Exception {
        User u = investor();
        String before = realState(u);
        WhatIfService service = new WhatIfService();
        var outcome = service.run(service.snapshot(u.userId()),
                List.of(new WhatIfEngine.Shock(WhatIfEngine.Scope.SECTOR, "IT", bd("-15"))));
        assertTrue(outcome.change().signum() < 0);
        assertEquals("TCS", outcome.lines().get(0).symbol());
        assertEquals(before, realState(u));
    }

    @Test
    void backtestsAreSavedSeparatelyReproducibleAndHarmless() throws Exception {
        User u = investor();
        String before = realState(u);
        BacktestService service = new BacktestService();
        for (StrategyType type : StrategyType.values()) {
            var report = service.run(u.userId(), backtest(type, 282));
            var reopened = service.open(u.userId(), report.runId());
            assertEquals(report.result().finalCapital(), reopened.result().finalCapital());
            assertEquals(report.result().trades(), reopened.result().trades());
            assertEquals((long) report.result().trades().size(),
                    scalar("SELECT COUNT(*) FROM backtest_trades WHERE run_id = ?", report.runId()));
        }
        assertEquals(3, service.history(u.userId()).size());
        assertEquals(before, realState(u));
        assertThrows(ValidationException.class, () -> service.run(u.userId(), backtest(StrategyType.BUY_AND_HOLD, 283)),
                "cannot use future prices");
        User other = newUser();
        int run = service.history(u.userId()).get(0).runId();
        assertThrows(ValidationException.class, () -> service.open(other.userId(), run));
        assertThrows(ValidationException.class, () -> service.delete(other.userId(), run));
        service.delete(u.userId(), run);
        assertEquals(2, service.history(u.userId()).size());
    }

    @Test
    void riskReportForEmptyAndInvestedPortfolios() throws Exception {
        RiskService risk = new RiskService();
        RiskService.Report empty = risk.report(newUser().userId());
        assertEquals("No holdings", empty.spread());
        assertNull(empty.volatility());
        assertNull(empty.concentrationIndex());
        assertTrue(empty.stressTests().isEmpty());
        RiskService.Report r = risk.report(investor().userId());
        assertEquals(3, r.sectorAllocation().size());
        assertTrue(r.volatility().signum() > 0);
        assertEquals(3, r.correlation().symbols().size());
        assertEquals(1.0, r.correlation().matrix()[0][0], 1e-9);
        assertEquals(4, r.stressTests().size());
        assertTrue(r.stressTests().get(0).changePercent().signum() < 0);
        assertFalse(r.spread().toLowerCase().contains("safe"));
    }

    @Test
    void performanceMatchesLedgerAndHistory() throws Exception {
        User u = investor();
        trading.sell(u.userId(), stock("TCS").getId(), 5);
        var r = new PerformanceService().report(u.userId());
        assertEquals(4, r.totalTrades());
        assertEquals(1, r.sales());
        assertEquals(1, r.profitableSales() + r.losingSales());
        BigDecimal byStock = r.profitByStock().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, byStock.compareTo(r.portfolio().totalProfit()));
        assertEquals(22, r.curve().size());
        assertEquals(100000, r.curve().get(0).benchmark(), 1e-6);
        assertFalse(r.monthlyReturns().isEmpty());
        var fresh = new PerformanceService().report(newUser().userId());
        assertNull(fresh.winRate());
        assertNull(fresh.best());
    }

    @Test
    void watchlistAndGoals() {
        User u = newUser();
        WatchlistService watch = new WatchlistService();
        int tcs = stock("TCS").getId();
        watch.add(u.userId(), tcs);
        watch.add(u.userId(), tcs);
        assertEquals(1, watch.quotes(u.userId()).size());
        watch.remove(u.userId(), tcs);
        assertTrue(watch.quotes(u.userId()).isEmpty());
        GoalService goals = new GoalService();
        goals.create(u.userId(), "Double", bd("200000"));
        InvestmentGoal g = goals.list(u.userId()).get(0);
        assertEquals(bd("50.00"), g.progressPercent());
        assertFalse(g.achieved());
        goals.update(u.userId(), g.id(), "Small", bd("90000"));
        assertTrue(goals.list(u.userId()).get(0).achieved());
        assertThrows(ValidationException.class, () -> goals.create(u.userId(), "Zero", BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> goals.create(u.userId(), " ", BigDecimal.ONE));
        goals.delete(u.userId(), g.id());
        assertTrue(goals.list(u.userId()).isEmpty());
    }

    @Test
    void everyReportCanBeExported() throws Exception {
        User u = investor();
        int run = new ScenarioService().start(u.userId(), 2).runId();
        new ScenarioService().runToEnd(u.userId());
        int bt = new BacktestService().run(u.userId(), backtest(StrategyType.MOVING_AVERAGE, 300)).runId();
        ReportService reports = new ReportService();
        for (Path file : List.of(reports.exportPortfolio(u.userId()), reports.exportTransactions(u.userId()),
                reports.exportPerformance(u.userId()), reports.exportBacktest(u.userId(), bt),
                reports.exportScenario(u.userId(), run))) {
            assertTrue(Files.size(file) > 100, file.toString());
        }
        String text = Files.readString(reports.exportTransactions(u.userId()));
        assertEquals(4, text.lines().count());
    }

    @Test
    void snapshotIsReadOnlyAndResetStartsOver() throws IOException, Exception {
        User u = investor();
        SettingsService settings = new SettingsService();
        settings.saveSnapshot(u.userId());
        assertEquals(282, settings.readSnapshot(u.userId()).orElseThrow().state().currentDay());
        assertThrows(ValidationException.class, () -> settings.resetSimulation(u.userId(), "wrong"));
        assertEquals(3, count("transactions", u.userId()));
        new GoalService().create(u.userId(), "Keep me", bd("1000"));
        settings.resetSimulation(u.userId(), PASSWORD);
        Portfolio p = portfolios.getPortfolio(u.userId());
        assertEquals(bd("100000.00"), p.totalValue());
        assertTrue(p.holdings().isEmpty());
        assertEquals(261, time.state(u.userId()).currentDay());
        assertEquals(0, count("simulation_prices", u.userId()));
        assertEquals(1, new GoalService().list(u.userId()).size());
    }
}
