package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One stock's closing price on one simulation day. */
public record StockPrice(int stockId, int day, LocalDate date, BigDecimal price) {
}
