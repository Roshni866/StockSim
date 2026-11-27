package com.stocksim.simulation;

import com.stocksim.model.MarketEvent;
import com.stocksim.model.MarketScenario;
import com.stocksim.model.ScenarioRule;
import com.stocksim.model.Stock;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;

/**
 * The price rules of the simulator. Pure and deterministic: the same inputs
 * always produce the same price.
 *
 * Normal day: the user's price moves by the reference history's daily return,
 * plus the impact of any scripted event for the stock's sector.
 *
 * Scenario day: history is replaced by the scenario's rules,
 *   move = beta x market rules + sector rules + seeded noise.
 */
public class SimulationEngine {
    private static final double MAX_DAILY_MOVE = 0.20;
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.05");

    /** Price on a normal (non-scenario) day. */
    public BigDecimal historicalDay(BigDecimal previousPrice, BigDecimal referenceBefore, BigDecimal referenceNow,
                                    Stock stock, List<MarketEvent> events) {
        // Multiplying before dividing keeps an undisturbed price exactly equal to history.
        BigDecimal price = previousPrice.multiply(referenceNow)
                .divide(referenceBefore, 10, RoundingMode.HALF_UP);
        BigDecimal impact = events.stream()
                .filter(e -> "ALL".equals(e.sector()) || stock.getSector().equals(e.sector()))
                .map(MarketEvent::impactPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (impact.signum() != 0) {
            price = price.add(price.multiply(impact).divide(Money.HUNDRED, 10, RoundingMode.HALF_UP));
        }
        return Money.of(price).max(MIN_PRICE);
    }

    /** The scenario's rule-based move for one stock on one scenario day, as a fraction. */
    public double scenarioMove(MarketScenario scenario, long seed, int scenarioDay, Stock stock) {
        double percent = 0;
        for (ScenarioRule rule : scenario.rules()) {
            if (!rule.appliesOn(scenarioDay)) {
                continue;
            }
            if (rule.isMarketWide()) {
                percent += rule.dailyPercent().doubleValue() * stock.getBeta().doubleValue();
            } else if (rule.sector().equals(stock.getSector())) {
                percent += rule.dailyPercent().doubleValue();
            }
        }
        // One generator per (seed, day, stock) so results do not depend on the order of calls.
        Random random = new Random(seed * 1_000_003L + scenarioDay * 7_919L + stock.getSymbol().hashCode());
        percent += (random.nextDouble() * 2 - 1) * scenario.noisePercent().doubleValue();
        return Math.max(-MAX_DAILY_MOVE, Math.min(MAX_DAILY_MOVE, percent / 100));
    }

    public BigDecimal scenarioDay(BigDecimal previousPrice, MarketScenario scenario, long seed, int scenarioDay,
                                  Stock stock) {
        BigDecimal factor = BigDecimal.valueOf(1 + scenarioMove(scenario, seed, scenarioDay, stock));
        return Money.of(previousPrice.multiply(factor)).max(MIN_PRICE);
    }
}
