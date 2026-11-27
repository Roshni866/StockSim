package com.stocksim.backtesting;

import com.stocksim.model.TradeType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A hypothetical trade. profit is the round-trip result and is null for purchases. */
public record BacktestTrade(int day, LocalDate date, TradeType side, int quantity, BigDecimal price, BigDecimal fee,
                            BigDecimal profit, String note) {
}
