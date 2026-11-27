package com.stocksim.service;

import com.stocksim.analytics.Indicators;
import com.stocksim.model.Holding;
import com.stocksim.model.Portfolio;
import com.stocksim.model.PortfolioSnapshot;
import com.stocksim.model.TradeType;
import com.stocksim.model.Transaction;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Performance measured from the real ledger, value history and prices. */
public class PerformanceService {
    private final PortfolioService portfolios = new PortfolioService();
    private final MarketService market = new MarketService();
    private final SimulationService simulation = new SimulationService();

    /** Equity next to what the starting capital would be worth if it tracked the index. */
    public record Point(int day, java.time.LocalDate date, double equity, double benchmark) {
    }

    public record Report(Portfolio portfolio, int totalTrades, int sales, int profitableSales, int losingSales,
                         BigDecimal winRate, Map<String, BigDecimal> profitByStock, BigDecimal maxDrawdown,
                         BigDecimal benchmarkReturn, List<Point> curve, Map<YearMonth, BigDecimal> monthlyReturns) {
        /** Stock with the largest total profit, or null before any trading. */
        public Map.Entry<String, BigDecimal> best() {
            return profitByStock.entrySet().stream().findFirst().orElse(null);
        }

        public Map.Entry<String, BigDecimal> worst() {
            return profitByStock.entrySet().stream().reduce((a, b) -> b).orElse(null);
        }
    }

    public Report report(int userId) {
        Portfolio p = portfolios.getPortfolio(userId);
        List<Transaction> ledger = portfolios.transactions(userId);
        List<Transaction> sales = ledger.stream().filter(t -> t.type() == TradeType.SELL).toList();
        int wins = (int) sales.stream().filter(t -> sign(t) > 0).count();
        int losses = (int) sales.stream().filter(t -> sign(t) < 0).count();
        BigDecimal winRate = sales.isEmpty() ? null
                : Money.percent(BigDecimal.valueOf(wins), BigDecimal.valueOf(sales.size()));

        Map<String, BigDecimal> profit = new TreeMap<>();
        ledger.forEach(t -> profit.merge(t.symbol(),
                t.realizedProfit() == null ? Money.ZERO : t.realizedProfit(), BigDecimal::add));
        for (Holding h : p.holdings()) {
            profit.merge(h.getSymbol(), h.profitLoss(), BigDecimal::add);
        }
        Map<String, BigDecimal> ranked = new LinkedHashMap<>();
        profit.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> ranked.put(e.getKey(), e.getValue()));

        List<PortfolioSnapshot> history = portfolios.history(userId);
        BigDecimal drawdown = Money.of(Indicators.maxDrawdown(
                history.stream().mapToDouble(s -> s.totalValue().doubleValue()).toArray()));

        int start = simulation.state(userId).startDay();
        int today = simulation.state(userId).currentDay();
        Map<Integer, Double> index = new java.util.HashMap<>();
        market.index(userId, today - start + 1).forEach(x -> index.put(x.day(), x.level()));
        double capital = p.startingCapital().doubleValue();
        List<Point> curve = new ArrayList<>();
        for (PortfolioSnapshot s : history) {
            Double level = index.get(s.day());
            if (level != null) {
                curve.add(new Point(s.day(), s.date(), s.totalValue().doubleValue(), capital * level / 1000));
            }
        }
        BigDecimal benchmark = curve.isEmpty() ? Money.ZERO
                : Money.of((curve.get(curve.size() - 1).benchmark() / capital - 1) * 100);

        return new Report(p, ledger.size(), sales.size(), wins, losses, winRate, ranked, drawdown, benchmark,
                curve, monthlyReturns(history, p.startingCapital()));
    }

    /** Change in value over each calendar month of simulated dates. */
    public static Map<YearMonth, BigDecimal> monthlyReturns(List<PortfolioSnapshot> history, BigDecimal start) {
        Map<YearMonth, BigDecimal> monthEnd = new TreeMap<>();
        history.forEach(s -> monthEnd.put(YearMonth.from(s.date()), s.totalValue()));
        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal previous = start;
        for (Map.Entry<YearMonth, BigDecimal> e : monthEnd.entrySet()) {
            result.put(e.getKey(), Money.change(previous, e.getValue()));
            previous = e.getValue();
        }
        return result;
    }

    private static int sign(Transaction t) {
        return t.realizedProfit() == null ? 0 : t.realizedProfit().signum();
    }
}
