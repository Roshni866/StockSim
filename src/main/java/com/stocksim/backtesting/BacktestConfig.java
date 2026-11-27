package com.stocksim.backtesting;

import java.math.BigDecimal;

/** Everything needed to reproduce a backtest. */
public record BacktestConfig(int stockId, StrategyType strategy, int fromDay, int toDay, BigDecimal initialCapital,
                             BigDecimal feePercent, int shortPeriod, int longPeriod, int lookback,
                             BigDecimal entryPercent, BigDecimal exitPercent) {
}
