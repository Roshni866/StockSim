package com.stocksim.controller;

import com.stocksim.service.RiskService;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.Map;

public class RiskController extends PageController {
    @FXML private Pane metrics;
    @FXML private VBox warningsBox;
    @FXML private StackPane stockPie;
    @FXML private StackPane sectorPie;
    @FXML private StackPane correlationBox;
    @FXML private TableView<RiskService.Stress> stressTable;
    private final RiskService risk = new RiskService();

    @Override
    protected void onAttach() {
        Ui.table(stressTable, "Stress tests need at least one holding.");
        Ui.text(stressTable, "Scenario", 150, RiskService.Stress::name);
        Ui.text(stressTable, "Assumption", 260, RiskService.Stress::description);
        Ui.money(stressTable, "Portfolio after", 130, RiskService.Stress::valueAfter);
        Ui.signed(stressTable, "Change %", 90, RiskService.Stress::changePercent, Formats::signedPercent);
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> risk.report(id), this::render);
    }

    private void render(RiskService.Report r) {
        Ui.metrics(metrics,
                share("Largest stock exposure", r.largestStock(),
                        "Share of holdings value in one company. A large share means one company drives results."),
                share("Largest sector exposure", r.largestSector(),
                        "Companies in one sector often move together."),
                Ui.metric("Concentration index", r.concentrationIndex() == null ? Formats.NONE
                        : r.concentrationIndex().setScale(0, java.math.RoundingMode.HALF_UP).toPlainString(), null,
                        "Herfindahl index: sum of squared stock weights. 10,000 is a single stock; lower is "
                                + "more spread out."),
                Ui.metric("Spread of holdings", r.spread(), "accent",
                        "Describes diversification only. Spreading money out reduces some risks but never makes "
                                + "a portfolio safe."),
                Ui.metric("Historical volatility", Formats.percent(r.volatility()), null,
                        "Annualised variation of your current share mix over the last 60 days. Higher means "
                                + "bigger swings."),
                Ui.metric("Your maximum drawdown", Formats.percent(r.accountDrawdown()), null,
                        "Deepest fall in your actual portfolio value from an earlier peak."),
                Ui.metric("Current-mix drawdown", Formats.percent(r.mixDrawdown()), null,
                        "Deepest fall over the last 60 days had you held today's shares throughout."));
        warningsBox.getChildren().clear();
        if (r.warnings().isEmpty()) {
            warningsBox.getChildren().add(Ui.note(r.portfolio().holdings().isEmpty()
                    ? "You hold no shares, so there is no concentration to measure."
                    : "No concentration threshold is exceeded. This does not mean the portfolio is safe: all "
                            + "shares can fall together."));
        }
        r.warnings().forEach(w -> {
            Label label = Ui.note(w.level() + ":  " + w.message(), "warning",
                    w.level().equals("HIGH") ? "warning-high" : "warning-elevated");
            label.setMaxWidth(Double.MAX_VALUE);
            warningsBox.getChildren().add(label);
        });
        Charts.pie(stockPie, r.stockAllocation(), "No holdings.");
        Charts.pie(sectorPie, r.sectorAllocation(), "No holdings.");
        correlation(r.correlation());
        Ui.fill(stressTable, r.stressTests());
    }

    private VBox share(String caption, Map.Entry<String, BigDecimal> entry, String explanation) {
        return Ui.metric(caption, entry == null ? Formats.NONE
                : entry.getKey() + "  " + Formats.percent(entry.getValue()), null, explanation);
    }

    private void correlation(RiskService.Correlation c) {
        if (c.symbols().size() < 2) {
            Charts.empty(correlationBox, "Hold at least two stocks to compare how they move together.");
            return;
        }
        if (c.observations() < RiskService.MIN_CORRELATION_OBSERVATIONS) {
            Charts.empty(correlationBox, "At least " + RiskService.MIN_CORRELATION_OBSERVATIONS
                    + " days of returns are needed; only " + c.observations() + " are available.");
            return;
        }
        GridPane grid = new GridPane();
        grid.getStyleClass().add("heatmap");
        for (int i = 0; i < c.symbols().size(); i++) {
            grid.add(Ui.label(c.symbols().get(i), "muted"), i + 1, 0);
            grid.add(Ui.label(c.symbols().get(i), "muted"), 0, i + 1);
            for (int j = 0; j < c.symbols().size(); j++) {
                Double value = c.matrix()[i][j];
                Label cell = Ui.label(value == null ? Formats.NONE : String.format("%.2f", value), "heat-cell");
                if (value != null) {
                    String colour = value >= 0 ? "214,168,75" : "91,141,184";
                    cell.setStyle("-fx-background-color: rgba(" + colour + "," + (0.1 + Math.abs(value) * 0.5)
                            + ");");
                    Tooltip.install(cell, new Tooltip(c.symbols().get(i) + " vs " + c.symbols().get(j) + " over "
                            + c.observations() + " daily returns"));
                }
                grid.add(cell, j + 1, i + 1);
            }
        }
        correlationBox.getChildren().setAll(grid);
    }

    @FXML private void openWhatIf() {
        shell.open(Page.LAB);
        LabController lab = shell.controller(Page.LAB);
        lab.select(1);
    }
}
