package com.stocksim.model;

import java.math.BigDecimal;

/** Indicators for one stock. A null value means there is not enough history yet. */
public record StockAnalysis(Stock stock, BigDecimal price, BigDecimal change, BigDecimal dailyPercent,
                            BigDecimal weekPercent, BigDecimal monthPercent, BigDecimal movingAverage5,
                            BigDecimal movingAverage20, BigDecimal high52, BigDecimal low52, BigDecimal volatility,
                            BigDecimal averageReturn, BigDecimal momentum, String trend, String momentumLabel,
                            String riskLabel, int historyDays) {
}
