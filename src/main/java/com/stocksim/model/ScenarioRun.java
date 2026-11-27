package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One user's execution of a scenario. status is ACTIVE, COMPLETED or CANCELLED. */
public record ScenarioRun(int runId, int scenarioId, String title, long seed, int startDay, int endDay, int lastDay,
                          String status, BigDecimal startValue, BigDecimal endValue, LocalDateTime createdAt) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
