package com.stocksim.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Everything shown on a scenario results screen. */
public record ScenarioReport(ScenarioRun run, BigDecimal startIndex, BigDecimal endIndex, BigDecimal marketChange,
                             BigDecimal portfolioBefore, BigDecimal portfolioAfter, List<StockImpact> stocks,
                             Map<String, BigDecimal> sectorImpact, List<Transaction> trades, String summary) {

    /** Price movement of one stock over the scenario. */
    public record StockImpact(String symbol, String sector, BigDecimal startPrice, BigDecimal endPrice,
                              BigDecimal changePercent) {
    }

    /** Stocks are ordered best first. */
    public StockImpact best() {
        return stocks.get(0);
    }

    public StockImpact worst() {
        return stocks.get(stocks.size() - 1);
    }
}
