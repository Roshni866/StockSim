package com.stocksim.model;

import java.math.BigDecimal;
import java.util.List;

/** A predefined hypothetical market scenario. */
public record MarketScenario(int id, String code, String title, String description, int durationDays,
                             String severity, long seed, BigDecimal noisePercent, List<ScenarioRule> rules) {

    public String affectedSectors() {
        List<String> sectors = rules.stream().filter(r -> !r.isMarketWide()).map(ScenarioRule::sector)
                .distinct().toList();
        boolean wide = rules.stream().anyMatch(ScenarioRule::isMarketWide);
        String named = String.join(", ", sectors);
        if (!wide) {
            return named;
        }
        return sectors.isEmpty() ? "All sectors" : "All sectors; extra rules for " + named;
    }

    @Override
    public String toString() {
        return title;
    }
}
