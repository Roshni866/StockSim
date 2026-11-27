package com.stocksim.model;

import java.math.BigDecimal;

/** Current price information for a stock in one user's simulation. */
public record Quote(Stock stock, BigDecimal price, BigDecimal change, BigDecimal changePercent,
                    BigDecimal high52, BigDecimal low52) {
}
