package com.stocksim.service;

import com.stocksim.analytics.Indicators;
import com.stocksim.analytics.WhatIfEngine;
import com.stocksim.analytics.WhatIfEngine.Scope;
import com.stocksim.analytics.WhatIfEngine.Shock;
import com.stocksim.model.Holding;
import com.stocksim.model.Portfolio;
import com.stocksim.model.PortfolioSnapshot;
import com.stocksim.model.StockPrice;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/** Concentration, volatility, drawdown, correlation and stress analysis. */
public class RiskService {
    public static final int WINDOW = 60;
    public static final int MIN_CORRELATION_OBSERVATIONS = 30;
    private final PortfolioService portfolios = new PortfolioService();
    private final MarketService market = new MarketService();

    public record Warning(String level, String message) {
    }

    /** matrix[i][j] is null when there is not enough data. */
    public record Correlation(List<String> symbols, Double[][] matrix, int observations) {
    }

    public record Stress(String name, String description, BigDecimal valueAfter, BigDecimal changePercent) {
    }

    public record Report(Portfolio portfolio, Map<String, BigDecimal> stockAllocation,
                         Map<String, BigDecimal> sectorAllocation, BigDecimal concentrationIndex,
                         String spread, List<Warning> warnings, BigDecimal volatility,
                         BigDecimal accountDrawdown, BigDecimal mixDrawdown, Correlation correlation,
                         List<Stress> stressTests) {
        public Map.Entry<String, BigDecimal> largestStock() {
            return stockAllocation.entrySet().stream().findFirst().orElse(null);
        }

        public Map.Entry<String, BigDecimal> largestSector() {
            return sectorAllocation.entrySet().stream().findFirst().orElse(null);
        }
    }

    public Report report(int userId) {
        Portfolio p = portfolios.getPortfolio(userId);
        Map<String, BigDecimal> byStock = allocation(p.holdings(), false);
        Map<String, BigDecimal> bySector = allocation(p.holdings(), true);
        Map<String, double[]> returns = new LinkedHashMap<>();
        double[] mix = null;
        for (Holding h : p.holdings()) {
            double[] prices = market.series(userId, h.getStock().getId(), WINDOW + 1).stream()
                    .mapToDouble((StockPrice s) -> s.price().doubleValue()).toArray();
            returns.put(h.getSymbol(), Indicators.returns(prices));
            if (mix == null) {
                mix = new double[prices.length];
            }
            // Value of holding today's share counts on each past day.
            for (int i = 0; i < prices.length; i++) {
                mix[i] += prices[i] * h.getQuantity();
            }
        }
        BigDecimal volatility = null;
        BigDecimal mixDrawdown = null;
        if (mix != null) {
            OptionalDouble v = Indicators.volatility(mix, Math.min(WINDOW, mix.length - 1));
            volatility = v.isPresent() && mix.length > MIN_CORRELATION_OBSERVATIONS
                    ? Money.of(v.getAsDouble()) : null;
            mixDrawdown = mix.length > 1 ? Money.of(Indicators.maxDrawdown(mix)) : null;
        }
        List<PortfolioSnapshot> history = portfolios.history(userId);
        BigDecimal drawdown = Money.of(Indicators.maxDrawdown(
                history.stream().mapToDouble(s -> s.totalValue().doubleValue()).toArray()));
        BigDecimal index = concentrationIndex(byStock);
        return new Report(p, byStock, bySector, index, spread(byStock, bySector), warnings(byStock, bySector),
                volatility, drawdown, mixDrawdown, correlate(returns), stress(p));
    }

    /** Percentage of holdings value per stock or sector, largest first. */
    public static Map<String, BigDecimal> allocation(List<Holding> holdings, boolean bySector) {
        BigDecimal total = holdings.stream().map(Holding::marketValue).reduce(Money.ZERO, BigDecimal::add);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        holdings.forEach(h -> values.merge(bySector ? h.getSector() : h.getSymbol(), h.marketValue(),
                BigDecimal::add));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), Money.percent(e.getValue(), total)));
        return result;
    }

    /** Herfindahl-Hirschman index on a 0-10,000 scale, or null with no holdings. */
    public static BigDecimal concentrationIndex(Map<String, BigDecimal> allocation) {
        if (allocation.isEmpty()) {
            return null;
        }
        return Money.of(allocation.values().stream().map(v -> v.multiply(v)).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static List<Warning> warnings(Map<String, BigDecimal> byStock, Map<String, BigDecimal> bySector) {
        List<Warning> result = new ArrayList<>();
        addWarnings(result, bySector, "Your %s sector allocation represents %s%% of your current holdings value.",
                ConfigLoader.getDecimal("risk.sector.elevated", "40"),
                ConfigLoader.getDecimal("risk.sector.high", "60"));
        addWarnings(result, byStock, "%s alone represents %s%% of your current holdings value.",
                ConfigLoader.getDecimal("risk.stock.elevated", "25"),
                ConfigLoader.getDecimal("risk.stock.high", "40"));
        return result;
    }

    private static void addWarnings(List<Warning> result, Map<String, BigDecimal> allocation, String template,
                                    BigDecimal elevated, BigDecimal high) {
        allocation.forEach((name, pct) -> {
            if (pct.compareTo(elevated) >= 0) {
                boolean isHigh = pct.compareTo(high) >= 0;
                result.add(new Warning(isHigh ? "HIGH" : "ELEVATED", String.format(template, name,
                        pct.toPlainString()) + " (" + (isHigh ? "high" : "elevated") + " threshold "
                        + (isHigh ? high : elevated).toPlainString() + "%)"));
            }
        });
    }

    static String spread(Map<String, BigDecimal> byStock, Map<String, BigDecimal> bySector) {
        if (byStock.isEmpty()) {
            return "No holdings";
        }
        boolean concentrated = warnings(byStock, bySector).stream().anyMatch(w -> w.level().equals("HIGH"));
        if (concentrated || bySector.size() == 1) {
            return "Concentrated";
        }
        return byStock.size() >= 5 && bySector.size() >= 3 ? "Broadly spread" : "Partly spread";
    }

    /** Correlation of daily returns between held stocks. */
    static Correlation correlate(Map<String, double[]> returns) {
        List<String> symbols = new ArrayList<>(returns.keySet());
        Double[][] matrix = new Double[symbols.size()][symbols.size()];
        int observations = 0;
        for (int i = 0; i < symbols.size(); i++) {
            observations = returns.get(symbols.get(i)).length;
            for (int j = 0; j < symbols.size(); j++) {
                OptionalDouble value = Indicators.correlation(returns.get(symbols.get(i)),
                        returns.get(symbols.get(j)), MIN_CORRELATION_OBSERVATIONS);
                matrix[i][j] = value.isPresent() ? value.getAsDouble() : null;
            }
        }
        return new Correlation(symbols, matrix, observations);
    }

    private List<Stress> stress(Portfolio p) {
        if (p.holdings().isEmpty()) {
            return List.of();
        }
        String sector = allocation(p.holdings(), true).keySet().iterator().next();
        String symbol = allocation(p.holdings(), false).keySet().iterator().next();
        return List.of(
                stress(p, "Market -10%", "Every holding falls 10%.",
                        new Shock(Scope.MARKET, null, BigDecimal.valueOf(-10))),
                stress(p, "Market -25%", "A severe fall in every holding.",
                        new Shock(Scope.MARKET, null, BigDecimal.valueOf(-25))),
                stress(p, sector + " -20%", "Your largest sector falls 20%.",
                        new Shock(Scope.SECTOR, sector, BigDecimal.valueOf(-20))),
                stress(p, symbol + " -30%", "Your largest holding falls 30%.",
                        new Shock(Scope.STOCK, symbol, BigDecimal.valueOf(-30))));
    }

    private Stress stress(Portfolio p, String name, String description, Shock shock) {
        WhatIfEngine.Outcome o = WhatIfEngine.run(p.cash(), p.holdings(), List.of(shock));
        return new Stress(name, description, o.hypotheticalValue(), o.changePercent());
    }
}
