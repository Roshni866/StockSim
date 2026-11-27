package com.stocksim.controller;

import com.stocksim.model.*;
import com.stocksim.model.ScenarioReport.StockImpact;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

public class ScenariosController extends PageController {
    @FXML private HBox controlsBox;
    @FXML private VBox activeBox;
    @FXML private Label activeLabel;
    @FXML private ProgressBar progress;
    @FXML private FlowPane cards;
    @FXML private TableView<ScenarioRun> runTable;
    @FXML private VBox resultBox;
    @FXML private Label resultTitle;
    @FXML private Label summaryLabel;
    @FXML private Pane resultMetrics;
    @FXML private StackPane sectorChart;
    @FXML private TableView<StockImpact> stockTable;
    @FXML private TableView<Transaction> tradeTable;

    private final ScenarioService scenarios = new ScenarioService();
    private final ReportService reports = new ReportService();
    private Integer shownRun;
    private Integer knownNewest;
    private boolean filling;

    private record Data(List<MarketScenario> scenarios, List<ScenarioRun> runs, ScenarioRun active,
                        ScenarioReport report) {
    }

    @Override
    protected void onAttach() {
        controlsBox.getChildren().add(shell.timeControls());
        Ui.table(runTable, "No scenario runs yet. Start one above; it affects only your own simulation.");
        Ui.number(runTable, "Run", 50, ScenarioRun::runId, String::valueOf);
        Ui.text(runTable, "Scenario", 170, ScenarioRun::title);
        Ui.text(runTable, "Status", 90, ScenarioRun::status);
        Ui.text(runTable, "Days", 90, r -> r.startDay() + " → " + r.lastDay());
        Ui.money(runTable, "Value before", 110, ScenarioRun::startValue);
        Ui.money(runTable, "Value after", 110, ScenarioRun::endValue);
        runTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!filling && b != null && !Integer.valueOf(b.runId()).equals(shownRun)) {
                shownRun = b.runId();
                refresh();
            }
        });
        Ui.table(stockTable, "No results.");
        Ui.text(stockTable, "Stock", 100, StockImpact::symbol);
        Ui.text(stockTable, "Sector", 80, StockImpact::sector);
        Ui.money(stockTable, "Start price", 100, StockImpact::startPrice);
        Ui.money(stockTable, "End price", 100, StockImpact::endPrice);
        Ui.signed(stockTable, "Change %", 80, StockImpact::changePercent, Formats::signedPercent);
        Ui.table(tradeTable, "You made no trades during this scenario.");
        Ui.number(tradeTable, "Day", 50, Transaction::simulationDay, String::valueOf);
        Ui.text(tradeTable, "Stock", 90, Transaction::symbol);
        Ui.text(tradeTable, "Type", 60, t -> t.type().name());
        Ui.number(tradeTable, "Qty", 60, Transaction::quantity, String::valueOf);
        Ui.money(tradeTable, "Total", 100, Transaction::total);
    }

    @Override
    public void refresh() {
        int id = userId();
        Integer wanted = shownRun;
        Integer previousNewest = knownNewest;
        shell.load(() -> {
            List<ScenarioRun> runs = scenarios.runs(id);
            Integer show = wanted;
            boolean newRun = !runs.isEmpty() && !Integer.valueOf(runs.get(0).runId()).equals(previousNewest);
            if (show == null || newRun || runs.stream().noneMatch(r -> r.runId() == wanted)) {
                show = runs.isEmpty() ? null : runs.get(0).runId();
            }
            return new Data(scenarios.scenarios(), runs, scenarios.activeRun(id).orElse(null),
                    show == null ? null : scenarios.report(id, show));
        }, this::render);
    }

    private void render(Data d) {
        knownNewest = d.runs().isEmpty() ? null : d.runs().get(0).runId();
        ScenarioRun active = d.active();
        activeBox.setVisible(active != null);
        activeBox.setManaged(active != null);
        if (active != null) {
            int done = active.lastDay() - active.startDay();
            int all = active.endDay() - active.startDay();
            activeLabel.setText("RUNNING: " + active.title() + " — day " + done + " of " + all
                    + ". Advance time to apply its rules.");
            progress.setProgress((double) done / all);
        }
        cards.getChildren().setAll(d.scenarios().stream().map(s -> card(s, active != null)).toList());
        filling = true;
        runTable.getItems().setAll(d.runs());
        ScenarioReport r = d.report();
        shownRun = r == null ? null : r.run().runId();
        runTable.getItems().stream().filter(x -> Integer.valueOf(x.runId()).equals(shownRun)).findFirst()
                .ifPresent(x -> runTable.getSelectionModel().select(x));
        filling = false;
        resultBox.setVisible(r != null);
        resultBox.setManaged(r != null);
        if (r == null) {
            return;
        }
        resultTitle.setText((r.run().isActive() ? "Results so far — " : "Results — ") + r.run().title()
                + " (run " + r.run().runId() + ", hypothetical)");
        summaryLabel.setText(r.summary());
        Ui.metrics(resultMetrics, Ui.metric("Starting market index", r.startIndex().toPlainString()),
                Ui.metric("Ending market index", r.endIndex().toPlainString()),
                Ui.change("Market movement", r.marketChange()),
                Ui.money("Portfolio before", r.portfolioBefore()), Ui.money("Portfolio after", r.portfolioAfter()),
                Ui.metric("Best stock", r.best().symbol(), "positive",
                        Formats.signedPercent(r.best().changePercent())),
                Ui.metric("Worst stock", r.worst().symbol(), "negative",
                        Formats.signedPercent(r.worst().changePercent())),
                Ui.metric("Your trades", String.valueOf(r.trades().size())));
        Charts.signedBars(sectorChart, "Change %", r.sectorImpact(), "No sector data.");
        Ui.fill(stockTable, r.stocks());
        Ui.fill(tradeTable, r.trades());
    }

    private VBox card(MarketScenario s, boolean busy) {
        StringBuilder rules = new StringBuilder();
        s.rules().forEach(x -> rules.append("Days ").append(x.fromDay()).append('–').append(x.toDay()).append(": ")
                .append(x.isMarketWide() ? "Market (× beta)" : x.sector()).append(' ')
                .append(Formats.signedPercent(x.dailyPercent())).append(" per day\n"));
        rules.append("Noise ±").append(s.noisePercent().toPlainString()).append("%, seed ").append(s.seed());
        Button start = Ui.button("Start scenario", () -> start(s), "primary");
        start.setDisable(busy);
        VBox card = new VBox(5, Ui.label(s.title(), "card-title"),
                Ui.label(s.severity().toUpperCase() + " • " + s.durationDays() + " DAYS", "event-meta"),
                Ui.note(s.description()), Ui.note("Affects: " + s.affectedSectors()),
                Ui.note(rules.toString(), "hint"));
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        card.getChildren().addAll(gap, start);
        card.getStyleClass().add("scenario-card");
        card.setPrefWidth(330);
        return card;
    }

    private void start(MarketScenario s) {
        if (!Dialogs.confirm("Start the \"" + s.title() + "\" scenario?", "For the next " + s.durationDays()
                + " days your simulated prices follow hypothetical rules instead of history. The price changes "
                + "stay in your simulation.")) {
            return;
        }
        int id = userId();
        shell.act(() -> {
            scenarios.start(id, s.id());
        }, s.title() + " started. Use the time controls to play it out.");
    }

    @FXML private void runToEnd() {
        int id = userId();
        shell.act(() -> scenarios.runToEnd(id), shell::announce);
    }

    @FXML private void cancel() {
        if (Dialogs.confirm("Cancel the running scenario?", "Price changes already simulated are kept.")) {
            int id = userId();
            shell.act(() -> scenarios.cancel(id), "Scenario cancelled.");
        }
    }

    @FXML private void export() {
        if (shownRun != null) {
            int id = userId();
            int run = shownRun;
            shell.export(() -> reports.exportScenario(id, run));
        }
    }
}
