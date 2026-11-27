package com.stocksim.controller;

import com.stocksim.analytics.Indicators;
import com.stocksim.model.*;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.math.BigDecimal;
import java.util.*;

public class StockController extends PageController {
    @FXML private ComboBox<Stock> stockBox;
    @FXML private Pane metrics;
    @FXML private HBox periodBox;
    @FXML private CheckBox ma5Box;
    @FXML private CheckBox ma20Box;
    @FXML private StackPane chart;
    @FXML private Label chartNote;
    @FXML private GridPane indicatorGrid;
    @FXML private GridPane classGrid;
    @FXML private TableView<Row> priceTable;

    private final MarketService market = new MarketService();
    private final AnalysisService analysis = new AnalysisService();
    private final ToggleGroup periods = new ToggleGroup();
    private Integer stockId;
    private List<StockPrice> series = List.of();
    private boolean updating;

    /** One row of the price history table. */
    public record Row(StockPrice price, BigDecimal change) {
    }

    private record Data(List<Stock> stocks, StockAnalysis analysis, List<StockPrice> series) {
    }

    @Override
    protected void onAttach() {
        for (AnalysisService.Period period : AnalysisService.Period.values()) {
            ToggleButton button = new ToggleButton(period.toString());
            button.setUserData(period);
            button.setToggleGroup(periods);
            periodBox.getChildren().add(button);
            if (period == AnalysisService.Period.ONE_MONTH) {
                button.setSelected(true);
            }
        }
        periods.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
            } else {
                draw();
            }
        });
        ma5Box.selectedProperty().addListener((o, a, b) -> draw());
        ma20Box.selectedProperty().addListener((o, a, b) -> draw());
        stockBox.valueProperty().addListener((o, a, b) -> {
            if (!updating && b != null && b.getId() != stockId) {
                stockId = b.getId();
                refresh();
            }
        });
        Ui.table(priceTable, "No price history.");
        Ui.number(priceTable, "Day", 60, r -> r.price().day(), String::valueOf);
        Ui.number(priceTable, "Date", 110, r -> r.price().date(), Formats::date);
        Ui.money(priceTable, "Close", 110, r -> r.price().price());
        Ui.signed(priceTable, "Change %", 90, Row::change, Formats::signedPercent);
    }

    void select(int id) {
        stockId = id;
    }

    @Override
    public void refresh() {
        int user = userId();
        shell.load(() -> {
            List<Stock> stocks = market.stocks();
            int id = stockId != null ? stockId : stocks.get(0).getId();
            return new Data(stocks, analysis.analyse(user, id),
                    market.series(user, id, Indicators.TRADING_DAYS_PER_YEAR));
        }, this::render);
    }

    private void render(Data d) {
        StockAnalysis a = d.analysis();
        stockId = a.stock().getId();
        series = d.series();
        updating = true;
        stockBox.getItems().setAll(d.stocks());
        stockBox.setValue(a.stock());
        updating = false;
        Ui.metrics(metrics, Ui.metric("Company", a.stock().getName(), null, a.stock().getSector() + " sector"),
                Ui.metric("Simulated price", Formats.money(a.price()), "accent", a.stock().getSymbol()),
                Ui.profit("Day change", a.change()), Ui.change("Day change %", a.dailyPercent()));
        indicatorGrid.getChildren().clear();
        indicator("Daily change", Formats.signedPercent(a.dailyPercent()), "Latest close against the one before.");
        indicator("7-day change", Formats.signedPercent(a.weekPercent()), "Over the last 7 trading days.");
        indicator("30-day change", Formats.signedPercent(a.monthPercent()), "Over the last 30 trading days.");
        indicator("5-day moving average", Formats.money(a.movingAverage5()), "Mean of the last 5 closes.");
        indicator("20-day moving average", Formats.money(a.movingAverage20()), "Mean of the last 20 closes.");
        indicator("52-week high", Formats.money(a.high52()), "Highest close in the last 252 trading days.");
        indicator("52-week low", Formats.money(a.low52()), "Lowest close in the last 252 trading days.");
        indicator("Historical volatility", Formats.percent(a.volatility()),
                "Spread of the last 30 daily returns, annualised.");
        indicator("Average daily return", Formats.signedPercent(a.averageReturn()),
                "Mean of the last 30 daily returns.");
        indicator("Momentum (10-day)", Formats.signedPercent(a.momentum()), "Price change over 10 trading days.");
        classGrid.getChildren().clear();
        Ui.row(classGrid, "Trend", a.trend(), "accent");
        Ui.row(classGrid, "Momentum", a.momentumLabel(), "accent");
        Ui.row(classGrid, "Risk", a.riskLabel(), "accent");
        Ui.row(classGrid, "History used", a.historyDays() + " trading days");
        List<Row> rows = new ArrayList<>();
        for (int i = series.size() - 1; i >= Math.max(0, series.size() - 127); i--) {
            rows.add(new Row(series.get(i), i == 0 ? null
                    : Money.change(series.get(i - 1).price(), series.get(i).price())));
        }
        Ui.fill(priceTable, rows);
        draw();
    }

    private void indicator(String name, String value, String explanation) {
        int row = indicatorGrid.getRowCount();
        indicatorGrid.addRow(row, Ui.label(name, "muted"), Ui.label(value), Ui.note(explanation, "hint"));
    }

    private void draw() {
        if (series.isEmpty() || periods.getSelectedToggle() == null) {
            return;
        }
        AnalysisService.Period period = (AnalysisService.Period) periods.getSelectedToggle().getUserData();
        int from = Math.max(0, series.size() - period.points());
        List<StockPrice> shown = series.subList(from, series.size());
        double[] all = series.stream().mapToDouble(s -> s.price().doubleValue()).toArray();
        List<Charts.Line> lines = new ArrayList<>();
        lines.add(new Charts.Line("Close", shown.stream().map(s -> s.price().doubleValue()).toList()));
        // The averages use earlier closes, so they are complete across the visible range.
        if (ma5Box.isSelected()) {
            lines.add(new Charts.Line("5-day average", average(all, from, 5)));
        }
        if (ma20Box.isSelected()) {
            lines.add(new Charts.Line("20-day average", average(all, from, 20)));
        }
        Charts.lines(chart, "Price (₹)", shown.stream().map(StockPrice::date).toList(),
                lines.toArray(Charts.Line[]::new));
        BigDecimal move = Money.change(shown.get(0).price(), shown.get(shown.size() - 1).price());
        chartNote.setText(period + " view: " + Formats.signedPercent(move) + " between "
                + Formats.date(shown.get(0).date()) + " and " + Formats.date(shown.get(shown.size() - 1).date())
                + (period == AnalysisService.Period.ONE_DAY ? " (daily closing prices; no intraday data)." : "."));
    }

    private static List<Double> average(double[] all, int from, int length) {
        List<Double> result = new ArrayList<>();
        for (int i = from; i < all.length; i++) {
            var value = Indicators.movingAverage(Arrays.copyOf(all, i + 1), length);
            result.add(value.isPresent() ? value.getAsDouble() : null);
        }
        return result;
    }

    @FXML private void buy() {
        TradeDialog.open(shell, stockId, TradeType.BUY);
    }

    @FXML private void sell() {
        TradeDialog.open(shell, stockId, TradeType.SELL);
    }

    @FXML private void watch() {
        int user = userId();
        int id = stockId;
        shell.act(() -> new WatchlistService().add(user, id), "Added to your watchlist.");
    }

    @FXML private void createAlert() {
        GoalsController goals = shell.controller(Page.GOALS);
        goals.prepareAlert(stockId);
        shell.open(Page.GOALS);
    }

    @FXML private void back() {
        shell.open(Page.MARKET);
    }
}
