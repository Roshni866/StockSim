package com.stocksim.backtesting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Metrics, curves and trades from one backtest. totalTrades counts executions;
 * winning and losing count completed round trips. Null means "not meaningful".
 */
public record BacktestResult(BigDecimal initialCapital, BigDecimal finalCapital, BigDecimal totalReturn,
                             BigDecimal annualizedReturn, int winningTrades, int losingTrades, int roundTrips,
                             BigDecimal winRate, BigDecimal maxDrawdown, BigDecimal averageTradeReturn,
                             BigDecimal benchmarkReturn, List<Point> curve, List<BacktestTrade> trades) {

    /** One day of the strategy and benchmark equity curves; drawdown is zero or negative. */
    public record Point(int day, LocalDate date, double equity, double benchmark, double drawdown) {
    }
}
