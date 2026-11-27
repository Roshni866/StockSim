package com.stocksim.service;

import com.stocksim.backtesting.BacktestResult;
import com.stocksim.backtesting.BacktestTrade;
import com.stocksim.exception.DataAccessException;
import com.stocksim.model.Holding;
import com.stocksim.model.Portfolio;
import com.stocksim.model.ScenarioReport;
import com.stocksim.model.Transaction;
import com.stocksim.util.ConfigLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** CSV exports. Each export only contains data belonging to the given user. */
public class ReportService {
    private static final Logger LOGGER = Logger.getLogger(ReportService.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private final PortfolioService portfolios = new PortfolioService();
    private final PerformanceService performance = new PerformanceService();
    private final BacktestService backtests = new BacktestService();
    private final ScenarioService scenarios = new ScenarioService();
    private final SettingsService settings = new SettingsService();

    public Path exportPortfolio(int userId) {
        Portfolio p = portfolios.getPortfolio(userId);
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("Available cash", p.cash()));
        rows.add(row("Holdings value", p.holdingsValue()));
        rows.add(row("Invested capital", p.invested()));
        rows.add(row("Total value", p.totalValue()));
        rows.add(row("Realised P/L", p.realized()));
        rows.add(row("Unrealised P/L", p.unrealized()));
        rows.add(row("Overall P/L", p.totalProfit()));
        rows.add(row("Return %", p.returnPercent()));
        rows.add(row());
        rows.add(row("Symbol", "Company", "Sector", "Quantity", "Average price", "Current price", "Market value",
                "Invested value", "Unrealised P/L", "Return %"));
        for (Holding h : p.holdings()) {
            rows.add(row(h.getSymbol(), h.getStock().getName(), h.getSector(), h.getQuantity(), h.averagePrice(),
                    h.getCurrentPrice(), h.marketValue(), h.getCostBasis(), h.profitLoss(), h.returnPercent()));
        }
        return write(userId, "portfolio", rows);
    }

    public Path exportTransactions(int userId) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("Transaction ID", "Market date", "Simulation day", "Symbol", "Type", "Quantity", "Price",
                "Total", "Realised P/L", "Recorded at"));
        for (Transaction t : portfolios.transactions(userId)) {
            rows.add(row(t.id(), t.marketDate(), t.simulationDay(), t.symbol(), t.type(), t.quantity(), t.price(),
                    t.total(), t.realizedProfit(), t.createdAt()));
        }
        return write(userId, "transactions", rows);
    }

    public Path exportPerformance(int userId) {
        PerformanceService.Report r = performance.report(userId);
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("Initial capital", r.portfolio().startingCapital()));
        rows.add(row("Current equity", r.portfolio().totalValue()));
        rows.add(row("Total return %", r.portfolio().returnPercent()));
        rows.add(row("Realised P/L", r.portfolio().realized()));
        rows.add(row("Unrealised P/L", r.portfolio().unrealized()));
        rows.add(row("Total trades", r.totalTrades()));
        rows.add(row("Profitable sales", r.profitableSales()));
        rows.add(row("Loss-making sales", r.losingSales()));
        rows.add(row("Win rate %", r.winRate()));
        rows.add(row("Maximum drawdown %", r.maxDrawdown()));
        rows.add(row("Benchmark return %", r.benchmarkReturn()));
        rows.add(row());
        rows.add(row("Symbol", "Total P/L"));
        r.profitByStock().forEach((s, v) -> rows.add(row(s, v)));
        rows.add(row());
        rows.add(row("Month", "Return %"));
        r.monthlyReturns().forEach((m, v) -> rows.add(row(m, v)));
        rows.add(row());
        rows.add(row("Day", "Date", "Equity", "Benchmark"));
        r.curve().forEach(x -> rows.add(row(x.day(), x.date(), fixed(x.equity()), fixed(x.benchmark()))));
        return write(userId, "performance", rows);
    }

    public Path exportBacktest(int userId, int runId) {
        BacktestService.Report report = backtests.open(userId, runId);
        BacktestResult r = report.result();
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("Historical strategy evaluation - not a guarantee of future results"));
        rows.add(row("Stock", report.symbol()));
        rows.add(row("Strategy", report.config().strategy()));
        rows.add(row("From day", report.config().fromDay(), "To day", report.config().toDay()));
        rows.add(row("Transaction cost %", report.config().feePercent()));
        rows.add(row("Short period", report.config().shortPeriod(), "Long period", report.config().longPeriod()));
        rows.add(row("Lookback", report.config().lookback(), "Entry %", report.config().entryPercent(),
                "Exit %", report.config().exitPercent()));
        rows.add(row("Initial capital", r.initialCapital()));
        rows.add(row("Final capital", r.finalCapital()));
        rows.add(row("Total return %", r.totalReturn()));
        rows.add(row("Annualised return %", r.annualizedReturn()));
        rows.add(row("Executions", r.trades().size()));
        rows.add(row("Winning trades", r.winningTrades(), "Losing trades", r.losingTrades()));
        rows.add(row("Win rate %", r.winRate()));
        rows.add(row("Maximum drawdown %", r.maxDrawdown()));
        rows.add(row("Average trade return %", r.averageTradeReturn()));
        rows.add(row("Benchmark return %", r.benchmarkReturn()));
        rows.add(row());
        rows.add(row("Date", "Side", "Quantity", "Price", "Fee", "Profit", "Note"));
        for (BacktestTrade t : r.trades()) {
            rows.add(row(t.date(), t.side(), t.quantity(), t.price(), t.fee(), t.profit(), t.note()));
        }
        rows.add(row());
        rows.add(row("Date", "Strategy equity", "Benchmark", "Drawdown %"));
        r.curve().forEach(x -> rows.add(row(x.date(), fixed(x.equity()), fixed(x.benchmark()), fixed(x.drawdown()))));
        return write(userId, "backtest" + runId, rows);
    }

    public Path exportScenario(int userId, int runId) {
        ScenarioReport r = scenarios.report(userId, runId);
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("HYPOTHETICAL scenario simulation - not historical market events"));
        rows.add(row("Scenario", r.run().title()));
        rows.add(row("Status", r.run().status()));
        rows.add(row("Seed", r.run().seed()));
        rows.add(row("Start day", r.run().startDay(), "Last day", r.run().lastDay()));
        rows.add(row("Starting index", r.startIndex(), "Ending index", r.endIndex(), "Change %", r.marketChange()));
        rows.add(row("Portfolio before", r.portfolioBefore(), "Portfolio after", r.portfolioAfter()));
        rows.add(row("Summary", r.summary()));
        rows.add(row());
        rows.add(row("Sector", "Average change %"));
        r.sectorImpact().forEach((s, v) -> rows.add(row(s, v)));
        rows.add(row());
        rows.add(row("Symbol", "Sector", "Start price", "End price", "Change %"));
        r.stocks().forEach(s -> rows.add(row(s.symbol(), s.sector(), s.startPrice(), s.endPrice(),
                s.changePercent())));
        rows.add(row());
        rows.add(row("Trade day", "Symbol", "Type", "Quantity", "Price", "Total"));
        r.trades().forEach(t -> rows.add(row(t.simulationDay(), t.symbol(), t.type(), t.quantity(), t.price(),
                t.total())));
        return write(userId, "scenario" + runId, rows);
    }

    private static Object[] row(Object... cells) {
        return cells;
    }

    private static String fixed(double value) {
        return com.stocksim.util.Money.of(value).toPlainString();
    }

    /** Quotes cells when needed and stops text being run as a spreadsheet formula. */
    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof java.math.BigDecimal d ? d.toPlainString() : value.toString();
        if (value instanceof String && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private Path write(int userId, String name, List<Object[]> rows) {
        String chosen = settings.get(userId).exportDir();
        Path dir = Path.of(chosen != null ? chosen : ConfigLoader.get("export.dir", "reports"));
        Path file = dir.resolve("user" + userId + "_" + name + "_" + LocalDateTime.now().format(STAMP) + ".csv")
                .toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write('﻿');
                for (Object[] row : rows) {
                    List<String> cells = new ArrayList<>();
                    for (Object cell : row) {
                        cells.add(csvCell(cell));
                    }
                    out.write(String.join(",", cells));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            throw new DataAccessException("Could not write the report: " + e.getMessage(), e);
        }
        LOGGER.info("Report written: " + file);
        return file;
    }
}
