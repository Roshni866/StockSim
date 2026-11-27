package com.stocksim;

import com.stocksim.analytics.Indicators;
import com.stocksim.analytics.WhatIfEngine;
import com.stocksim.analytics.WhatIfEngine.Scope;
import com.stocksim.analytics.WhatIfEngine.Shock;
import com.stocksim.backtesting.BacktestConfig;
import com.stocksim.backtesting.BacktestEngine;
import com.stocksim.backtesting.BacktestResult;
import com.stocksim.backtesting.StrategyType;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Holding;
import com.stocksim.model.Stock;
import com.stocksim.model.StockPrice;
import com.stocksim.model.TradeType;
import com.stocksim.service.AnalysisService;
import com.stocksim.service.RiskService;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;
import com.stocksim.util.PasswordUtil;
import com.stocksim.util.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.stocksim.TestSupport.bd;
import static org.junit.jupiter.api.Assertions.*;

/** Pure calculations: no database or screen needed. */
class CalculationTest {

    private static List<StockPrice> series(double... closes) {
        List<StockPrice> result = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            result.add(new StockPrice(1, i + 1, LocalDate.of(2020, 1, 1).plusDays(i), Money.of(closes[i])));
        }
        return result;
    }

    private static BacktestConfig config(StrategyType type, int from, int to, String capital, String fee,
                                         int shortP, int longP, int lookback, String entry, String exit) {
        return new BacktestConfig(1, type, from, to, bd(capital), bd(fee), shortP, longP, lookback, bd(entry),
                bd(exit));
    }

    private static BacktestResult run(BacktestConfig c, List<StockPrice> prices) {
        double[] flat = new double[c.toDay() - c.fromDay() + 1];
        Arrays.fill(flat, 1000);
        return new BacktestEngine().run(c, prices, flat);
    }

    @Test
    void moneyAndIndianFormatting() {
        assertEquals("₹1,00,000.00", Formats.money(bd("100000")));
        assertEquals("-₹12,34,567.89", Formats.money(bd("-1234567.89")));
        assertEquals("₹999.50", Formats.money(bd("999.5")));
        assertEquals("+5.00%", Formats.signedPercent(bd("5")));
        assertEquals(bd("0.30"), Money.times(bd("0.10"), 3));
        assertEquals(bd("0.00"), Money.percent(bd("5"), BigDecimal.ZERO));
    }

    @Test
    void validationRules() {
        assertTrue(Validator.isValidEmail("a@b.in"));
        assertFalse(Validator.isValidEmail("a@b"));
        assertTrue(Validator.isValidPassword("abcd1234"));
        assertFalse(Validator.isValidPassword("abcdefgh"));
        assertFalse(Validator.isValidPassword("a1" + "x".repeat(80)));
        assertThrows(ValidationException.class, () -> Validator.positiveInt("1.5", "Quantity"));
        assertThrows(ValidationException.class, () -> Validator.positiveAmount("0", "Target"));
        assertEquals(bd("150000.00"), Validator.positiveAmount("1,50,000", "Target"));
    }

    @Test
    void passwordHashingIsSaltedAndVerifiable() {
        String first = PasswordUtil.hash("password123");
        assertNotEquals(first, PasswordUtil.hash("password123"));
        assertTrue(first.startsWith("$2"));
        assertTrue(PasswordUtil.matches("password123", first));
        assertFalse(PasswordUtil.matches("password124", first));
    }

    @Test
    void indicators() {
        double[] p = {100, 110, 99, 120, 120};
        assertEquals(0, Indicators.change(p, 1).orElseThrow(), 1e-9);
        assertEquals(20, Indicators.change(p, 4).orElseThrow(), 1e-9);
        assertTrue(Indicators.change(p, 5).isEmpty());
        assertEquals(113, Indicators.movingAverage(p, 3).orElseThrow(), 1e-9);
        assertTrue(Indicators.movingAverage(p, 6).isEmpty());
        assertEquals(0, Indicators.volatility(new double[] {5, 5, 5, 5}, 3).orElseThrow(), 1e-9);
        double[] r = {0.1, -0.1};
        assertEquals(Math.sqrt(0.02) * Math.sqrt(252) * 100,
                Indicators.volatility(new double[] {100, 110, 99}, 2).orElseThrow(), 1e-6);
        assertTrue(Indicators.correlation(r, r, 30).isEmpty(), "too little data gives no correlation");
        double[] x = new double[40];
        double[] y = new double[40];
        for (int i = 0; i < 40; i++) {
            x[i] = Math.sin(i);
            y[i] = -x[i];
        }
        assertEquals(-1, Indicators.correlation(x, y, 30).orElseThrow(), 1e-9);
    }

    @Test
    void maximumDrawdownIsTheDeepestFallFromAPeak() {
        assertEquals(25, Indicators.maxDrawdown(new double[] {100, 120, 90, 130, 110}), 1e-9);
        assertEquals(0, Indicators.maxDrawdown(new double[] {1, 2, 3}), 1e-9);
        assertEquals(0, Indicators.maxDrawdown(new double[0]), 1e-9);
    }

    @Test
    void analysisReportsMissingHistoryInsteadOfGuessing() {
        Stock stock = new Stock(1, "AAA", "A", "IT", BigDecimal.ONE);
        var analysis = AnalysisService.analyse(stock, series(10, 11, 12));
        assertNull(analysis.movingAverage20());
        assertNull(analysis.volatility());
        assertEquals("Insufficient data", analysis.trend());
        assertEquals(bd("9.09"), analysis.dailyPercent());
    }

    // ---------------- what-if ----------------

    private static Holding holding(String symbol, String sector, int quantity, String price) {
        return new Holding(new Stock(symbol.hashCode(), symbol, symbol, sector, BigDecimal.ONE), quantity,
                Money.times(bd(price), quantity), bd(price));
    }

    private final List<Holding> portfolio = List.of(holding("AAA", "IT", 10, "100.00"),
            holding("BBB", "Banking", 5, "200.00"));

    @Test
    void whatIfPositiveAndNegativeMovements() {
        var up = WhatIfEngine.run(bd("500"), portfolio, List.of(new Shock(Scope.STOCK, "AAA", bd("10"))));
        assertEquals(bd("2500.00"), up.originalValue());
        assertEquals(bd("2600.00"), up.hypotheticalValue());
        assertEquals(bd("4.00"), up.changePercent());
        var down = WhatIfEngine.run(bd("500"), portfolio, List.of(new Shock(Scope.SECTOR, "IT", bd("-15"))));
        assertEquals(bd("-150.00"), down.change());
        assertEquals(1, down.affectedHoldings());
        assertEquals(bd("1000.00"), portfolio.get(0).marketValue(), "input snapshot is unchanged");
    }

    @Test
    void whatIfCombinesSimultaneousMovementsAndNeverGoesBelowZero() {
        var o = WhatIfEngine.run(Money.ZERO, portfolio, List.of(new Shock(Scope.MARKET, null, bd("-60")),
                new Shock(Scope.SECTOR, "IT", bd("-70")), new Shock(Scope.STOCK, "BBB", bd("10"))));
        assertEquals(bd("500.00"), o.hypotheticalValue());
        assertThrows(ValidationException.class, () -> new Shock(Scope.MARKET, null, bd("-101")));
        assertThrows(ValidationException.class, () -> WhatIfEngine.run(Money.ZERO, portfolio, List.of()));
        var empty = WhatIfEngine.run(bd("100"), List.of(), List.of(new Shock(Scope.MARKET, null, bd("-10"))));
        assertEquals(bd("0.00"), empty.change());
    }

    // ---------------- backtesting ----------------

    @Test
    void buyAndHoldExecutesTheDayAfterTheDecision() {
        BacktestResult r = run(config(StrategyType.BUY_AND_HOLD, 1, 4, "1000", "0", 0, 0, 0, "0", "0"),
                series(5, 10, 20, 25));
        assertEquals(2, r.trades().get(0).day());
        assertEquals(100, r.trades().get(0).quantity());
        assertEquals(bd("2500.00"), r.finalCapital());
        assertEquals(bd("150.00"), r.totalReturn());
        assertEquals(1, r.winningTrades());
        assertEquals(bd("100.00"), r.winRate());
        assertNull(r.annualizedReturn(), "under a year is not annualised");
    }

    @Test
    void transactionCostsAreChargedAndCashIsNeverNegative() {
        BacktestResult r = run(config(StrategyType.BUY_AND_HOLD, 1, 3, "1000", "1", 0, 0, 0, "0", "0"),
                series(10, 10, 10));
        assertEquals(99, r.trades().get(0).quantity());
        assertEquals(bd("9.90"), r.trades().get(0).fee());
        assertEquals(bd("980.20"), r.finalCapital());
        assertEquals(1, r.losingTrades());
    }

    @Test
    void movingAverageCrossoverBuysAndSellsOnCrosses() {
        BacktestResult r = run(config(StrategyType.MOVING_AVERAGE, 1, 14, "10000", "0", 2, 4, 0, "0", "0"),
                series(20, 19, 18, 17, 16, 15, 18, 22, 26, 28, 24, 18, 14, 12));
        assertEquals(TradeType.BUY, r.trades().get(0).side());
        assertEquals(TradeType.SELL, r.trades().get(1).side());
        assertTrue(r.trades().get(0).day() > 7);
        assertTrue(r.maxDrawdown().signum() > 0);
    }

    @Test
    void momentumTradesOnThresholds() {
        BacktestResult r = run(config(StrategyType.MOMENTUM, 1, 10, "10000", "0", 0, 0, 2, "5", "-5"),
                series(10, 10, 10, 11, 12, 13, 11, 10, 9, 9));
        assertEquals(5, r.trades().get(0).day(), "signal on day 4, executed on day 5");
        assertEquals(TradeType.SELL, r.trades().get(1).side());
        assertEquals(8, r.trades().get(1).day());
    }

    @Test
    void futurePricesCannotChangeEarlierDecisions() {
        double[] a = new double[80];
        for (int i = 0; i < a.length; i++) {
            a[i] = 100 + 20 * Math.sin(i / 4.0);
        }
        double[] b = a.clone();
        for (int i = 50; i < b.length; i++) {
            b[i] = 500 - i;
        }
        for (StrategyType type : StrategyType.values()) {
            BacktestConfig c = config(type, 1, 80, "100000", "0.1", 3, 10, 5, "2", "-2");
            var first = run(c, series(a)).trades().stream().filter(t -> t.day() <= 50 && t.day() < 50).toList();
            var second = run(c, series(b)).trades().stream().filter(t -> t.day() < 50).toList();
            assertEquals(first, second, type + " looked ahead");
        }
    }

    @Test
    void zeroTradesAreReportedWithoutMadeUpStatistics() {
        BacktestResult r = run(config(StrategyType.MOMENTUM, 1, 8, "5000", "0", 0, 0, 2, "50", "-50"),
                series(10, 10, 10, 10, 10, 10, 10, 10));
        assertTrue(r.trades().isEmpty());
        assertEquals(bd("5000.00"), r.finalCapital());
        assertNull(r.winRate());
        assertNull(r.averageTradeReturn());
    }

    @Test
    void insufficientHistoryAndBadSettingsAreRejected() {
        assertThrows(ValidationException.class, () -> run(
                config(StrategyType.MOVING_AVERAGE, 1, 5, "1000", "0", 5, 20, 0, "0", "0"), series(1, 2, 3, 4, 5)));
        assertThrows(ValidationException.class, () -> run(
                config(StrategyType.MOVING_AVERAGE, 1, 5, "1000", "0", 10, 5, 0, "0", "0"), series(1, 2, 3, 4, 5)));
        assertThrows(ValidationException.class, () -> run(
                config(StrategyType.MOMENTUM, 1, 5, "1000", "0", 0, 0, 2, "1", "5"), series(1, 2, 3, 4, 5)));
        assertThrows(ValidationException.class, () -> run(
                config(StrategyType.BUY_AND_HOLD, 1, 3, "-1", "0", 0, 0, 0, "0", "0"), series(1, 2, 3)));
    }

    @Test
    void longPeriodsAreAnnualised() {
        double[] closes = new double[505];
        Arrays.fill(closes, 100);
        closes[504] = 121;
        BacktestResult r = run(config(StrategyType.BUY_AND_HOLD, 1, 505, "10000", "0", 0, 0, 0, "0", "0"),
                series(closes));
        assertEquals(bd("10.00"), r.annualizedReturn(), "21% over two trading years");
    }

    // ---------------- risk ----------------

    @Test
    void allocationConcentrationAndWarnings() {
        var stocks = RiskService.allocation(portfolio, false);
        assertEquals(bd("50.00"), stocks.get("AAA"));
        var sectors = RiskService.allocation(List.of(holding("AAA", "IT", 9, "100.00"),
                holding("BBB", "Banking", 11, "100.00")), true);
        assertEquals(bd("45.00"), sectors.get("IT"));
        assertEquals(bd("5000.00"), RiskService.concentrationIndex(stocks));
        assertTrue(RiskService.warnings(stocks, sectors).stream().anyMatch(w -> w.message()
                .startsWith("Your IT sector allocation represents 45.00% of your current holdings value.")));
        assertNull(RiskService.concentrationIndex(RiskService.allocation(List.of(), false)));
    }
}
