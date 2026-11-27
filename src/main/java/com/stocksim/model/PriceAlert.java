package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A price alert. The triggered fields stay null until it fires, which happens once. */
public record PriceAlert(int id, int stockId, String symbol, Direction direction, BigDecimal target,
                         boolean triggered, Integer triggeredDay, LocalDate triggeredDate,
                         BigDecimal triggeredPrice) {

    public enum Direction {
        ABOVE, BELOW
    }

    public boolean isMetBy(BigDecimal price) {
        return direction == Direction.ABOVE ? price.compareTo(target) >= 0 : price.compareTo(target) <= 0;
    }
}
