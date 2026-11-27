package com.stocksim.controller;

import com.stocksim.backtesting.*;
import com.stocksim.dao.BacktestDAO.SavedRun;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Stock;
import com.stocksim.model.TradeType;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Validator;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.util.*;

public class BacktestController extends PageController {
    private static final String CUSTOM = "Custom dates";
    private static final Map<String, Integer> PERIODS = new LinkedHashMap<>();
    static {
        PERIODS.put("Last 6 months", 126);
        PERIODS.put("Last 1 year", 253);
        PERIODS.put("Last 2 years", 505);
        PERIODS.put("All available", Integer.MAX_VALUE);
        PERIODS.put(CUSTOM, 0);
    }
    @FXML private ComboBox<Stock> stockBox;
    @FXML private ComboBox<StrategyType> strategyBox;
    @FXML private ComboBox<String> periodBox;
    @FXML private DatePicker fromPicker;
    @FXML private DatePicker toPicker;
    @FXML private TextField capitalField;
    @FXML private TextField feeField;
    @FXML private HBox averageBox;
    @FXML private TextField shortField;
    @FXML private TextField longField;
    @FXML private HBox momentumBox;
    @FXML private TextField lookbackField;
    @FXML private TextField entryField;
    @FXML private TextField exitField;
    @FXML private Label ruleLabel;
    @FXML private Label errorLabel;
    @FXML private VBox resultBox;
    @FXML private Label resultTitle;
    @FXML private Pane metrics;
    @FXML private StackPane equityChart;
    @FXML private StackPane drawdownChart;
    @FXML private TableView<BacktestTrade> tradeTable;
    @FXML private TableView<SavedRun> savedTable;

    private final BacktestService backtests = new BacktestService();
    private final MarketService market = new MarketService();
    private final SimulationService simulation = new SimulationService();
    private final ReportService reports = new ReportService();
    private TreeMap<LocalDate, Integer> dates = new TreeMap<>();
    private Integer shownRun;

    private record Data(List<Stock> stocks, Map<Integer, LocalDate> dates, List<SavedRun> saved) {
    }

    @Override
    protected void onAttach() {
        strategyBox.getItems().setAll(StrategyType.values());
        strategyBox.valueProperty().addListener((o, a, b) -> showParameters(b));
        strategyBox.setValue(StrategyType.MOVING_AVERAGE);
        periodBox.getItems().setAll(PERIODS.keySet());
        periodBox.setValue("Last 1 year");
        periodBox.valueProperty().addListener((o, a, b) -> applyPeriod());
        Ui.table(tradeTable, "This strategy made no trades in the period: its entry rule was never met.");
        Ui.number(tradeTable, "Date", 100, BacktestTrade::date, Formats::date);
        Ui.text(tradeTable, "Side", 55, t -> t.side().name());
        Ui.number(tradeTable, "Qty", 60, BacktestTrade::quantity, String::valueOf);
        Ui.money(tradeTable, "Price", 95, BacktestTrade::price);
        Ui.money(tradeTable, "Fee", 75, BacktestTrade::fee);
        Ui.signed(tradeTable, "Profit", 100, BacktestTrade::profit, Formats::signedMoney);
        Ui.text(tradeTable, "Reason", 140, BacktestTrade::note);
        Ui.table(savedTable, "No saved backtests yet.");
        Ui.number(savedTable, "Run", 50, SavedRun::runId, String::valueOf);
        Ui.text(savedTable, "Stock", 90, SavedRun::symbol);
        Ui.text(savedTable, "Strategy", 170, r -> r.config().strategy().toString());
        Ui.text(savedTable, "Days", 90, r -> r.config().fromDay() + " → " + r.config().toDay());
        Ui.money(savedTable, "Final capital", 110, SavedRun::finalCapital);
        Ui.signed(savedTable, "Return %", 80, SavedRun::totalReturn, Formats::signedPercent);
        Ui.number(savedTable, "Run on", 130, SavedRun::createdAt, Formats::dateTime);
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    private void showParameters(StrategyType type) {
        boolean average = type == StrategyType.MOVING_AVERAGE;
        boolean momentum = type == StrategyType.MOMENTUM;
        averageBox.setVisible(average);
        averageBox.setManaged(average);
        momentumBox.setVisible(momentum);
        momentumBox.setManaged(momentum);
        ruleLabel.setText(switch (type) {
            case BUY_AND_HOLD -> "Buys as many whole shares as possible and keeps them to the end.";
            case MOVING_AVERAGE -> "Buys when the short average crosses above the long average; sells on the "
                    + "opposite cross.";
            case MOMENTUM -> "Buys when the lookback return reaches the entry level; sells when it falls to the "
                    + "exit level.";
        } + " Decisions use only past closes and execute at the next day's close. No short selling. Open "
                + "positions are closed on the last day.");
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> new Data(market.stocks(), market.dates(1, simulation.state(id).currentDay()),
                backtests.history(id)), d -> {
            if (stockBox.getItems().isEmpty()) {
                stockBox.getItems().setAll(d.stocks());
                stockBox.getSelectionModel().selectFirst();
            }
            dates = new TreeMap<>();
            d.dates().forEach((day, date) -> dates.put(date, day));
            applyPeriod();
            Ui.fill(savedTable, d.saved());
        });
    }

    private void applyPeriod() {
        boolean custom = CUSTOM.equals(periodBox.getValue());
        fromPicker.setDisable(!custom);
        toPicker.setDisable(!custom);
        if (dates.isEmpty()) {
            return;
        }
        LocalDate last = dates.lastKey();
        if (!custom) {
            List<LocalDate> all = new ArrayList<>(dates.keySet());
            int length = PERIODS.get(periodBox.getValue());
            fromPicker.setValue(all.get(Math.max(0, all.size() - Math.min(length, all.size()))));
            toPicker.setValue(last);
        } else if (toPicker.getValue() == null || toPicker.getValue().isAfter(last)) {
            toPicker.setValue(last);
        }
    }

    private BacktestConfig readForm() {
        if (stockBox.getValue() == null || fromPicker.getValue() == null || toPicker.getValue() == null) {
            throw new ValidationException("Choose a stock and a period.");
        }
        Map.Entry<LocalDate, Integer> from = dates.ceilingEntry(fromPicker.getValue());
        Map.Entry<LocalDate, Integer> to = dates.floorEntry(toPicker.getValue());
        if (from == null || to == null || toPicker.getValue().isAfter(dates.lastKey())) {
            throw new ValidationException("Dates must be between " + Formats.date(dates.firstKey())
                    + " and your current simulation date " + Formats.date(dates.lastKey()) + ".");
        }
        StrategyType type = strategyBox.getValue();
        boolean average = type == StrategyType.MOVING_AVERAGE;
        boolean momentum = type == StrategyType.MOMENTUM;
        return new BacktestConfig(stockBox.getValue().getId(), type, from.getValue(), to.getValue(),
                Validator.positiveAmount(capitalField.getText(), "Initial capital"),
                Validator.decimal(feeField.getText(), "Transaction cost"),
                average ? Validator.positiveInt(shortField.getText(), "Short period") : 0,
                average ? Validator.positiveInt(longField.getText(), "Long period") : 0,
                momentum ? Validator.positiveInt(lookbackField.getText(), "Lookback") : 0,
                momentum ? Validator.decimal(entryField.getText(), "Entry threshold") : java.math.BigDecimal.ZERO,
                momentum ? Validator.decimal(exitField.getText(), "Exit threshold") : java.math.BigDecimal.ZERO);
    }

    @FXML private void run() {
        errorLabel.setText("");
        try {
            BacktestConfig config = readForm();
            int id = userId();
            shell.act(() -> backtests.run(id, config), report -> {
                render(report);
                shell.toast("Backtest saved", "Run " + report.runId() + " added to your history.",
                        "toast-success");
            });
        } catch (ValidationException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML private void openSaved() {
        SavedRun selected = savedTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            errorLabel.setText("Select a saved backtest first.");
            return;
        }
        int id = userId();
        shell.load(() -> backtests.open(id, selected.runId()), this::render);
    }

    @FXML private void deleteSaved() {
        SavedRun selected = savedTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            errorLabel.setText("Select a saved backtest first.");
            return;
        }
        if (Dialogs.confirm("Delete backtest run " + selected.runId() + "?", "This cannot be undone.")) {
            int id = userId();
            if (Integer.valueOf(selected.runId()).equals(shownRun)) {
                resultBox.setVisible(false);
                resultBox.setManaged(false);
                shownRun = null;
            }
            shell.act(() -> backtests.delete(id, selected.runId()), "Backtest deleted.");
        }
    }

    @FXML private void export() {
        if (shownRun != null) {
            int id = userId();
            int run = shownRun;
            shell.export(() -> reports.exportBacktest(id, run));
        }
    }

    private void render(BacktestService.Report report) {
        shownRun = report.runId();
        BacktestResult r = report.result();
        resultBox.setVisible(true);
        resultBox.setManaged(true);
        resultTitle.setText("Run " + report.runId() + ": " + report.config().strategy() + " on " + report.symbol()
                + ", " + Formats.date(r.curve().get(0).date()) + " to "
                + Formats.date(r.curve().get(r.curve().size() - 1).date()));
        Ui.metrics(metrics, Ui.money("Initial capital", r.initialCapital()),
                Ui.money("Final capital", r.finalCapital()), Ui.change("Total return", r.totalReturn()),
                r.annualizedReturn() == null ? Ui.metric("Annualised return", Formats.NONE, null,
                        "Needs at least one trading year.") : Ui.change("Annualised return", r.annualizedReturn()),
                Ui.change("Benchmark (equal-weight index)", r.benchmarkReturn()),
                Ui.metric("Total trades", String.valueOf(r.trades().size()), null,
                        r.roundTrips() + " completed round trips"),
                Ui.metric("Winning trades", String.valueOf(r.winningTrades())),
                Ui.metric("Losing trades", String.valueOf(r.losingTrades())),
                Ui.metric("Win rate", Formats.percent(r.winRate())),
                Ui.metric("Maximum drawdown", Formats.percent(r.maxDrawdown())),
                Ui.change("Average trade return", r.averageTradeReturn()));
        List<LocalDate> axis = r.curve().stream().map(BacktestResult.Point::date).toList();
        LineChart<Number, Number> chart = Charts.lineChart("Equity (₹)", axis,
                new Charts.Line("Strategy", r.curve().stream().map(BacktestResult.Point::equity).toList()),
                new Charts.Line("Benchmark", r.curve().stream().map(BacktestResult.Point::benchmark).toList()));
        chart.getStyleClass().add("marker-chart");
        List<XYChart.Series<Number, Number>> markers = List.of(markers(r, TradeType.BUY, "Buy"),
                markers(r, TradeType.SELL, "Sell"));
        chart.getData().addAll(markers);
        // The chart resets series style classes whenever series are added, so ours comes last.
        markers.forEach(series -> series.getNode().getStyleClass().add("marker-line"));
        chart.setLegendVisible(true);
        equityChart.getChildren().setAll(chart);
        Charts.lines(drawdownChart, "Drawdown %", axis, new Charts.Line("Drawdown",
                r.curve().stream().map(BacktestResult.Point::drawdown).toList()));
        Ui.fill(tradeTable, r.trades());
    }

    /** Trades drawn as points on the equity curve; the series' connecting line is hidden. */
    private static XYChart.Series<Number, Number> markers(BacktestResult r, TradeType side, String name) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        int first = r.curve().get(0).day();
        for (BacktestTrade t : r.trades()) {
            if (t.side() == side) {
                int index = t.day() - first;
                XYChart.Data<Number, Number> data = new XYChart.Data<>(index, r.curve().get(index).equity());
                Region marker = new Region();
                Tooltip.install(marker, new Tooltip(side + " " + t.quantity() + " at " + Formats.money(t.price())
                        + " on " + Formats.date(t.date())));
                data.setNode(marker);
                series.getData().add(data);
            }
        }
        return series;
    }
}
