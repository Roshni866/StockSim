package com.stocksim;

import com.stocksim.exception.ValidationException;
import com.stocksim.model.*;
import com.stocksim.service.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.stocksim.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    private final SimulationService time = new SimulationService();
    private final ScenarioService scenarios = new ScenarioService();
    private final MarketService market = new MarketService();
    private final TradingService trading = new TradingService();
    private final PortfolioService portfolios = new PortfolioService();
    private final AlertService alerts = new AlertService();

    @BeforeAll
    static void setUp() {
        database();
    }

    private BigDecimal price(User u, String symbol) {
        return market.series(u.userId(), stock(symbol).getId(), 1).get(0).price();
    }

    private List<BigDecimal> prices(User u) {
        return market.quotes(u.userId()).stream().map(Quote::price).toList();
    }

    @Test
    void dayWeekAndMonthMoveTheClockAndRecordPrices() {
        User u = newUser();
        BigDecimal before = price(u, "TCS");
        assertEquals(262, time.advance(u.userId(), SimulationService.DAY).newDay());
        assertNotEquals(before, price(u, "TCS"));
        assertEquals(267, time.advance(u.userId(), SimulationService.WEEK).newDay());
        assertEquals(288, time.advance(u.userId(), SimulationService.MONTH).newDay());
        assertEquals(27L * 10, count("simulation_prices", u.userId()));
        assertEquals(28, portfolios.history(u.userId()).size());
    }

    @Test
    void oneUsersClockDoesNotMoveAnothersMarket() {
        User a = newUser();
        User b = newUser();
        List<BigDecimal> before = prices(b);
        scenarios.start(a.userId(), 3);
        time.advance(a.userId(), 10);
        assertEquals(before, prices(b));
        assertEquals(261, time.state(b.userId()).currentDay());
    }

    @Test
    void normalDaysFollowHistoryExceptForScriptedEvents() {
        User u = newUser();
        time.advance(u.userId(), 4);
        BigDecimal reference = (BigDecimal) scalar(
                "SELECT price FROM stock_history WHERE stock_id = ? AND simulation_day = 265", stock("TCS").getId());
        assertEquals(reference, price(u, "TCS"), "no event yet: identical to history");
        var result = time.advance(u.userId(), 1);
        assertEquals(1, result.events().size(), "the day-5 IT event");
        BigDecimal itReference = (BigDecimal) scalar(
                "SELECT price FROM stock_history WHERE stock_id = ? AND simulation_day = 266", stock("TCS").getId());
        double ratio = price(u, "TCS").doubleValue() / itReference.doubleValue();
        assertEquals(1.015, ratio, 0.0005, "IT shares get +1.5%");
        BigDecimal bankReference = (BigDecimal) scalar(
                "SELECT price FROM stock_history WHERE stock_id = ? AND simulation_day = 266", stock("SBIN").getId());
        assertEquals(bankReference, price(u, "SBIN"), "other sectors are untouched");
    }

    @Test
    void scenariosAreReproducible() {
        User a = newUser();
        User b = newUser();
        scenarios.start(a.userId(), 6);
        scenarios.start(b.userId(), 6);
        scenarios.runToEnd(a.userId());
        for (int i = 0; i < 6; i++) {
            time.advance(b.userId(), 5);
        }
        assertEquals(291, time.state(a.userId()).currentDay());
        assertEquals(prices(a), prices(b), "same seed, same result, whatever the step size");
    }

    @Test
    void sectorScenarioMovesThatSectorAndProducesAReport() throws Exception {
        User u = newUser();
        trading.buy(u.userId(), stock("TCS").getId(), 20);
        ScenarioRun run = scenarios.start(u.userId(), 4);
        assertThrows(ValidationException.class, () -> scenarios.start(u.userId(), 1));
        trading.buy(u.userId(), stock("ITC").getId(), 1);
        var result = scenarios.runToEnd(u.userId());
        assertEquals(run.runId(), result.completedRunId());
        assertNull(time.state(u.userId()).activeRunId());
        ScenarioReport report = scenarios.report(u.userId(), run.runId());
        assertEquals("COMPLETED", report.run().status());
        BigDecimal it = report.sectorImpact().get("IT");
        assertTrue(it.doubleValue() > 15, "IT boom: " + it);
        assertTrue(report.sectorImpact().get("Banking").abs().doubleValue() < 8);
        assertEquals("IT", report.best().sector());
        assertEquals(10, report.stocks().size());
        assertEquals(1, report.trades().size(), "only the trade made during the scenario");
        assertTrue(report.portfolioAfter().compareTo(report.portfolioBefore()) > 0);
        assertEquals(portfolios.getPortfolio(u.userId()).totalValue(), report.portfolioAfter());
        assertTrue(report.summary().contains("not historical events"));
    }

    @Test
    void crashAndBankingScenariosFallAndBullRises() {
        for (int[] expected : new int[][] {{1, 1}, {2, -1}, {3, -1}, {5, -1}}) {
            User u = newUser();
            int run = scenarios.start(u.userId(), expected[0]).runId();
            scenarios.runToEnd(u.userId());
            ScenarioReport report = scenarios.report(u.userId(), run);
            assertEquals(expected[1], report.marketChange().signum(), report.run().title());
        }
        assertEquals(6, scenarios.scenarios().size());
    }

    @Test
    void cancellingKeepsPricesAndStopsTheRules() {
        User u = newUser();
        int run = scenarios.start(u.userId(), 3).runId();
        time.advance(u.userId(), 3);
        assertEquals(3, scenarios.report(u.userId(), run).run().lastDay() - 261);
        List<BigDecimal> during = prices(u);
        scenarios.cancel(u.userId());
        assertEquals(during, prices(u));
        assertEquals("CANCELLED", scenarios.report(u.userId(), run).run().status());
        assertTrue(time.advance(u.userId(), 1).events().stream().noneMatch(MarketEvent::fromScenario));
    }

    @Test
    void alertsTriggerExactlyOnceWithDayAndPrice() {
        User u = newUser();
        int tcs = stock("TCS").getId();
        alerts.create(u.userId(), tcs, PriceAlert.Direction.ABOVE, bd("0.01"));
        alerts.create(u.userId(), tcs, PriceAlert.Direction.BELOW, bd("0.01"));
        assertThrows(ValidationException.class,
                () -> alerts.create(u.userId(), tcs, PriceAlert.Direction.ABOVE, bd("0.01")));
        assertThrows(ValidationException.class,
                () -> alerts.create(u.userId(), tcs, PriceAlert.Direction.ABOVE, BigDecimal.ZERO));
        var first = time.advance(u.userId(), 5);
        assertEquals(1, first.triggeredAlerts().size());
        assertEquals(262, first.triggeredAlerts().get(0).triggeredDay());
        assertTrue(time.advance(u.userId(), 5).triggeredAlerts().isEmpty());
        PriceAlert fired = alerts.list(u.userId()).stream().filter(PriceAlert::triggered).findFirst().orElseThrow();
        assertNotNull(fired.triggeredPrice());
        assertNotNull(fired.triggeredDate());
        assertEquals(1, alerts.unseenCount(u.userId()));
        alerts.markSeen(u.userId());
        assertEquals(0, alerts.unseenCount(u.userId()));
    }

    @Test
    void portfolioIsRevaluedAfterTimePasses() throws Exception {
        User u = newUser();
        trading.buy(u.userId(), stock("RELIANCE").getId(), 10);
        time.advance(u.userId(), 21);
        Portfolio p = portfolios.getPortfolio(u.userId());
        assertEquals(price(u, "RELIANCE").multiply(BigDecimal.TEN).setScale(2), p.holdingsValue());
        var history = portfolios.history(u.userId());
        assertEquals(p.totalValue(), history.get(history.size() - 1).totalValue());
    }

    @Test
    void theClockStopsAtTheEndOfTheData() {
        User u = newUser();
        SimulationService.AdvanceResult r = null;
        for (int i = 0; i < 6; i++) {
            r = time.advance(u.userId(), 260);
        }
        assertTrue(r.reachedEnd());
        assertEquals(market.lastDay(), time.state(u.userId()).currentDay());
        assertEquals(0, time.advance(u.userId(), 1).daysAdvanced());
        assertThrows(ValidationException.class, () -> scenarios.start(u.userId(), 1));
        assertThrows(ValidationException.class, () -> time.advance(u.userId(), 0));
    }
}
