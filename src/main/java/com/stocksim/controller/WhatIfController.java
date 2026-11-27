package com.stocksim.controller;

import com.stocksim.analytics.WhatIfEngine;
import com.stocksim.analytics.WhatIfEngine.Line;
import com.stocksim.analytics.WhatIfEngine.Scope;
import com.stocksim.analytics.WhatIfEngine.Shock;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Portfolio;
import com.stocksim.model.Stock;
import com.stocksim.service.MarketService;
import com.stocksim.service.WhatIfService;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Validator;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

public class WhatIfController extends PageController {
    @FXML private ComboBox<Scope> scopeBox;
    @FXML private ComboBox<String> targetBox;
    @FXML private TextField percentField;
    @FXML private ListView<Shock> shockList;
    @FXML private Label messageLabel;
    @FXML private VBox resultBox;
    @FXML private Label summaryLabel;
    @FXML private Pane metrics;
    @FXML private StackPane compareChart;
    @FXML private StackPane sectorChart;
    @FXML private TableView<Line> table;

    private final WhatIfService whatIf = new WhatIfService();
    private final MarketService market = new MarketService();
    private List<Stock> stocks = List.of();
    private boolean ran;

    private record Data(List<Stock> stocks, Portfolio snapshot) {
    }

    @Override
    protected void onAttach() {
        scopeBox.getItems().setAll(Scope.values());
        scopeBox.valueProperty().addListener((o, a, b) -> targets());
        scopeBox.setValue(Scope.SECTOR);
        shockList.setPlaceholder(Ui.note("No movements added. Example: IT sector, -15%."));
        shockList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Shock item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.describe());
            }
        });
        Ui.table(table, "You hold no shares, so there is nothing to revalue. Buy shares first.");
        Ui.text(table, "Stock", 100, Line::symbol);
        Ui.text(table, "Sector", 80, Line::sector);
        Ui.number(table, "Qty", 55, Line::quantity, String::valueOf);
        Ui.signed(table, "Applied %", 80, Line::appliedPercent, Formats::signedPercent);
        Ui.money(table, "Price now", 95, Line::price);
        Ui.money(table, "Hypothetical", 95, Line::newPrice);
        Ui.money(table, "Value before", 105, Line::before);
        Ui.money(table, "Value after", 105, Line::after);
        Ui.signed(table, "Change", 100, Line::change, Formats::signedMoney);
        hideResults();
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> new Data(market.stocks(), whatIf.snapshot(id)), d -> {
            boolean first = stocks.isEmpty();
            stocks = d.stocks();
            if (first) {
                targets();
            }
            if (ran) {
                render(whatIf.run(d.snapshot(), List.copyOf(shockList.getItems())));
            }
        });
    }

    private void targets() {
        Scope scope = scopeBox.getValue();
        targetBox.setDisable(scope == Scope.MARKET);
        targetBox.getItems().setAll(scope == Scope.STOCK ? stocks.stream().map(Stock::getSymbol).toList()
                : scope == Scope.SECTOR ? stocks.stream().map(Stock::getSector).distinct().sorted().toList()
                : List.of());
        targetBox.getSelectionModel().selectFirst();
    }

    @FXML private void add() {
        try {
            Shock shock = new Shock(scopeBox.getValue(), targetBox.getValue(),
                    Validator.decimal(percentField.getText(), "Percentage change"));
            shockList.getItems().add(shock);
            percentField.clear();
            changed("");
        } catch (ValidationException e) {
            messageLabel.setText(e.getMessage());
        }
    }

    @FXML private void remove() {
        int index = shockList.getSelectionModel().getSelectedIndex();
        if (index >= 0) {
            shockList.getItems().remove(index);
            changed("");
        } else {
            messageLabel.setText("Select a movement in the list to remove it.");
        }
    }

    private void changed(String message) {
        ran = false;
        hideResults();
        messageLabel.setText(message);
    }

    /** Shows which holdings the movements would touch, without calculating the outcome. */
    @FXML private void preview() {
        List<Shock> shocks = List.copyOf(shockList.getItems());
        if (shocks.isEmpty()) {
            messageLabel.setText("Add at least one hypothetical movement first.");
            return;
        }
        int id = userId();
        shell.load(() -> whatIf.snapshot(id), snapshot -> {
            List<String> touched = snapshot.holdings().stream()
                    .filter(h -> shocks.stream().anyMatch(s -> s.appliesTo(h)))
                    .map(h -> h.getSymbol() + " (" + Formats.money(h.marketValue()) + ")").toList();
            messageLabel.setText("Preview: " + shocks.size() + " movement(s). "
                    + (touched.isEmpty() ? "None of your holdings are affected."
                    : "Affected holdings: " + String.join(", ", touched) + ".")
                    + " Movements hitting the same holding are added together.");
        });
    }

    @FXML private void run() {
        List<Shock> shocks = List.copyOf(shockList.getItems());
        if (shocks.isEmpty()) {
            messageLabel.setText("Add at least one hypothetical movement first.");
            return;
        }
        int id = userId();
        shell.load(() -> whatIf.run(whatIf.snapshot(id), shocks), outcome -> {
            ran = true;
            messageLabel.setText("");
            render(outcome);
        });
    }

    @FXML private void reset() {
        shockList.getItems().clear();
        percentField.clear();
        changed("Experiment cleared. Your real portfolio was never changed.");
    }

    private void hideResults() {
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    private void render(WhatIfEngine.Outcome o) {
        resultBox.setVisible(true);
        resultBox.setManaged(true);
        summaryLabel.setText(o.summary());
        String exposure = o.biggestExposure().map(WhatIfEngine.SectorImpact::sector).orElse(Formats.NONE);
        Ui.metrics(metrics, Ui.money("Original value", o.originalValue()),
                Ui.money("Hypothetical value", o.hypotheticalValue()), Ui.profit("Absolute change", o.change()),
                Ui.change("Percentage change", o.changePercent()),
                Ui.metric("Affected holdings", o.affectedHoldings() + " of " + o.lines().size()),
                Ui.metric("Biggest exposure", exposure, "accent", o.biggestExposure()
                        .map(s -> Formats.money(s.before()) + " held in this sector").orElse("No holdings")));
        Charts.comparisonBars(compareChart, "Value (₹)", List.of("Total value"), "Before",
                List.of(o.originalValue()), "After", List.of(o.hypotheticalValue()));
        if (o.sectors().isEmpty()) {
            Charts.empty(sectorChart, "No holdings, so no sector impact.");
        } else {
            java.util.Map<String, java.math.BigDecimal> impact = new java.util.LinkedHashMap<>();
            o.sectors().forEach(s -> impact.put(s.sector(), s.change()));
            Charts.signedBars(sectorChart, "Change (₹)", impact, "");
        }
        Ui.fill(table, o.lines());
    }
}
