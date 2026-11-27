package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Portfolio value at the end of one simulation day. */
public record PortfolioSnapshot(int day, LocalDate date, BigDecimal cash, BigDecimal holdingsValue,
                                BigDecimal totalValue) {
}
