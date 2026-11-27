package com.stocksim.model;

import java.math.BigDecimal;

/** A predefined daily movement between two scenario days. sector is null for market-wide rules. */
public record ScenarioRule(String sector, int fromDay, int toDay, BigDecimal dailyPercent, String headline) {
    public boolean isMarketWide() {
        return sector == null;
    }

    public boolean appliesOn(int scenarioDay) {
        return scenarioDay >= fromDay && scenarioDay <= toDay;
    }
}
