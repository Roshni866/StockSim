package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A target portfolio value with progress measured against the current value. */
public record InvestmentGoal(int id, String name, BigDecimal target, LocalDateTime createdAt,
                             BigDecimal currentValue, BigDecimal progressPercent) {
    public boolean achieved() {
        return currentValue.compareTo(target) >= 0;
    }
}
