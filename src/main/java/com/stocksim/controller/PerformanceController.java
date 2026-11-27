package com.stocksim.controller;

import com.stocksim.service.PerformanceService;
import com.stocksim.service.ReportService;
import com.stocksim.service.RiskService;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class PerformanceController extends PageController {
    @FXML private Pane metrics;
    @FXML private StackPane equityChart;
    @FXML private StackPane profitChart;
    @FXML private StackPane monthlyChart;
    @FXML private StackPane sectorPie;
    @FXML private StackPane allocationPie;
    private final PerformanceService performance = new PerformanceService();
    private final ReportService reports = new ReportService();

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> performance.report(id), this::render);
    }

    private void render(PerformanceService.Report r) {
        var p = r.portfolio();
        Ui.metrics(metrics, Ui.money("Initial capital", p.startingCapital()),
                Ui.money("Current equity", p.totalValue()), Ui.change("Total return", p.returnPercent()),
                Ui.change("Benchmark return", r.benchmarkReturn()), Ui.profit("Realised P/L", p.realized()),
                Ui.profit("Unrealised P/L", p.unrealized()),
                Ui.metric("Total trades", String.valueOf(r.totalTrades()), null, r.sales() + " sales"),
                Ui.metric("Profitable trades", String.valueOf(r.profitableSales())),
                Ui.metric("Loss-making trades", String.valueOf(r.losingSales())),
                Ui.metric("Win rate", Formats.percent(r.winRate()), null,
                        "Profitable sales divided by all sales."),
                Ui.metric("Maximum drawdown", Formats.percent(r.maxDrawdown())),
                performer("Best-performing stock", r.best()), performer("Worst-performing stock", r.worst()));
        Charts.lines(equityChart, "Value (₹)", r.curve().stream().map(PerformanceService.Point::date).toList(),
                new Charts.Line("Your equity", r.curve().stream().map(PerformanceService.Point::equity).toList()),
                new Charts.Line("Market benchmark",
                        r.curve().stream().map(PerformanceService.Point::benchmark).toList()));
        Charts.signedBars(profitChart, "P/L (₹)", r.profitByStock(), "Trade a stock to see profit and loss.");
        Map<String, BigDecimal> months = new LinkedHashMap<>();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM yy", java.util.Locale.ENGLISH);
        r.monthlyReturns().forEach((month, value) -> months.put(month.format(format), value));
        Charts.signedBars(monthlyChart, "Return %", months, "No history yet.");
        Charts.pie(sectorPie, RiskService.allocation(p.holdings(), true), "No holdings.");
        Map<String, BigDecimal> investment = new LinkedHashMap<>();
        investment.put("Cash", p.cash());
        p.holdings().forEach(h -> investment.put(h.getSymbol(), h.marketValue()));
        Charts.pie(allocationPie, investment, "No capital.");
    }

    private VBox performer(String caption, Map.Entry<String, BigDecimal> entry) {
        return entry == null ? Ui.metric(caption, Formats.NONE, null, "No trades yet.")
                : Ui.metric(caption, entry.getKey(), Formats.signClass(entry.getValue()),
                        Formats.signedMoney(entry.getValue()) + " total P/L");
    }

    @FXML private void export() {
        int id = userId();
        shell.export(() -> reports.exportPerformance(id));
    }
}
