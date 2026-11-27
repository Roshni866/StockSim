package com.stocksim.backtesting;

import com.stocksim.analytics.Indicators;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.StockPrice;
import com.stocksim.model.TradeType;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Replays history day by day. A decision made on one day's close is executed at
 * the NEXT day's close. Long only, whole shares, no borrowing. Any open position
 * is closed at the final close so every purchase has a measurable result.
 */
public class BacktestEngine {

    public static Strategy strategyFor(BacktestConfig config) {
        return switch (config.strategy()) {
            case BUY_AND_HOLD -> new BuyAndHold();
            case MOVING_AVERAGE -> new MovingAverageCrossover(config.shortPeriod(), config.longPeriod());
            case MOMENTUM -> new MomentumStrategy(config.lookback(), config.entryPercent().doubleValue(),
                    config.exitPercent().doubleValue());
        };
    }

    /**
     * @param prices    the stock's closes from the earliest known day up to config.toDay()
     * @param benchmark market index level for each day from fromDay to toDay
     */
    public BacktestResult run(BacktestConfig config, List<StockPrice> prices, double[] benchmark) {
        validate(config);
        Strategy strategy = strategyFor(config);
        double[] closes = prices.stream().mapToDouble(p -> p.price().doubleValue()).toArray();
        int first = indexOfDay(prices, config.fromDay());
        int last = indexOfDay(prices, config.toDay());
        int firstDecision = Math.max(first, strategy.warmup() - 1);
        if (first < 0 || last < 0 || firstDecision >= last) {
            throw new ValidationException("Not enough historical data: this strategy needs " + strategy.warmup()
                    + " days of prices before it can trade, plus at least one more day inside the period.");
        }
        if (benchmark.length != last - first + 1) {
            throw new IllegalArgumentException("Benchmark length does not match the period.");
        }

        BigDecimal capital = Money.of(config.initialCapital());
        BigDecimal cash = capital;
        int shares = 0;
        BigDecimal paid = Money.ZERO;
        Strategy.Signal pending = Strategy.Signal.HOLD;
        List<BacktestTrade> trades = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        List<Double> tradeReturns = new ArrayList<>();
        int wins = 0;
        int losses = 0;

        for (int i = first; i <= last; i++) {
            StockPrice today = prices.get(i);
            boolean finalDay = i == last;
            if (pending == Strategy.Signal.BUY && !finalDay) {
                int quantity = affordable(cash, today.price(), config.feePercent());
                if (quantity > 0) {
                    BigDecimal value = Money.times(today.price(), quantity);
                    BigDecimal fee = Money.percentOf(value, config.feePercent());
                    cash = cash.subtract(value).subtract(fee);
                    shares = quantity;
                    paid = value.add(fee);
                    trades.add(new BacktestTrade(today.day(), today.date(), TradeType.BUY, quantity, today.price(),
                            fee, null, "Signal"));
                }
            }
            boolean sellSignal = pending == Strategy.Signal.SELL;
            if (shares > 0 && (sellSignal || finalDay)) {
                BigDecimal value = Money.times(today.price(), shares);
                BigDecimal fee = Money.percentOf(value, config.feePercent());
                BigDecimal proceeds = value.subtract(fee);
                BigDecimal profit = proceeds.subtract(paid);
                cash = cash.add(proceeds);
                trades.add(new BacktestTrade(today.day(), today.date(), TradeType.SELL, shares, today.price(), fee,
                        profit, sellSignal ? "Signal" : "Closed at end of period"));
                tradeReturns.add(Money.percent(profit, paid).doubleValue());
                if (profit.signum() > 0) {
                    wins++;
                } else if (profit.signum() < 0) {
                    losses++;
                }
                shares = 0;
            }
            equity.add(cash.add(Money.times(today.price(), shares)).doubleValue());
            // Decide using only data up to today; it takes effect tomorrow.
            pending = i + 1 >= strategy.warmup() ? strategy.decide(Arrays.copyOf(closes, i + 1), shares > 0)
                    : Strategy.Signal.HOLD;
        }

        double[] curveValues = new double[equity.size() + 1];
        curveValues[0] = capital.doubleValue();
        for (int i = 0; i < equity.size(); i++) {
            curveValues[i + 1] = equity.get(i);
        }
        double[] drawdowns = Indicators.drawdowns(curveValues);
        List<BacktestResult.Point> curve = new ArrayList<>();
        for (int i = 0; i < equity.size(); i++) {
            StockPrice p = prices.get(first + i);
            curve.add(new BacktestResult.Point(p.day(), p.date(), equity.get(i),
                    capital.doubleValue() * benchmark[i] / benchmark[0], drawdowns[i + 1]));
        }
        BigDecimal totalReturn = Money.change(capital, cash);
        BigDecimal annualized = null;
        int periods = last - first;
        if (periods >= Indicators.TRADING_DAYS_PER_YEAR) {
            double growth = cash.doubleValue() / capital.doubleValue();
            annualized = Money.of((Math.pow(growth, (double) Indicators.TRADING_DAYS_PER_YEAR / periods) - 1) * 100);
        }
        int roundTrips = tradeReturns.size();
        BigDecimal winRate = roundTrips == 0 ? null
                : Money.percent(BigDecimal.valueOf(wins), BigDecimal.valueOf(roundTrips));
        BigDecimal average = roundTrips == 0 ? null
                : Money.of(tradeReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        BigDecimal benchmarkReturn = Money.of((benchmark[benchmark.length - 1] / benchmark[0] - 1) * 100);
        return new BacktestResult(capital, cash, totalReturn, annualized, wins, losses, roundTrips, winRate,
                Money.of(Indicators.maxDrawdown(curveValues)), average, benchmarkReturn, curve, trades);
    }

    private static void validate(BacktestConfig c) {
        if (c.initialCapital() == null || c.initialCapital().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Initial capital must be greater than zero.");
        }
        if (c.initialCapital().compareTo(new BigDecimal("1000000000")) > 0) {
            throw new ValidationException("Initial capital is too large.");
        }
        if (c.feePercent() == null || c.feePercent().signum() < 0 || c.feePercent().compareTo(BigDecimal.TEN) > 0) {
            throw new ValidationException("Transaction cost must be between 0% and 10%.");
        }
        if (c.fromDay() >= c.toDay()) {
            throw new ValidationException("The period must start before it ends.");
        }
    }

    /** Largest whole number of shares whose cost plus fee fits in the available cash. */
    static int affordable(BigDecimal cash, BigDecimal price, BigDecimal feePercent) {
        BigDecimal unit = price.multiply(BigDecimal.ONE.add(feePercent.movePointLeft(2)));
        int quantity = cash.divide(unit, 0, RoundingMode.DOWN).intValue();
        while (quantity > 0) {
            BigDecimal value = Money.times(price, quantity);
            if (value.add(Money.percentOf(value, feePercent)).compareTo(cash) <= 0) {
                break;
            }
            quantity--;
        }
        return quantity;
    }

    private static int indexOfDay(List<StockPrice> prices, int day) {
        for (int i = 0; i < prices.size(); i++) {
            if (prices.get(i).day() == day) {
                return i;
            }
        }
        return -1;
    }
}
