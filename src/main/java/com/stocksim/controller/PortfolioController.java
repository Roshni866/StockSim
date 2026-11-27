package com.stocksim.controller;

import com.stocksim.model.*;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.List;

public class PortfolioController extends PageController {
    @FXML private Pane metrics;
    @FXML private Pane performers;
    @FXML private TableView<Holding> table;
    @FXML private Button analyseButton;
    @FXML private Button buyButton;
    @FXML private Button sellButton;
    @FXML private StackPane historyChart;
    @FXML private StackPane stockPie;
    @FXML private StackPane sectorPie;
    private final PortfolioService portfolios = new PortfolioService();
    private final ReportService reports = new ReportService();

    private record Data(Portfolio portfolio, List<PortfolioSnapshot> history) {
    }

    @Override
    protected void onAttach() {
        Ui.table(table, "You hold no shares yet. Buy some in the Market Terminal to build a portfolio.");
        Ui.text(table, "Stock", 100, Holding::getSymbol);
        Ui.number(table, "Quantity", 70, Holding::getQuantity, String::valueOf);
        Ui.money(table, "Avg. price", 100, Holding::averagePrice);
        Ui.money(table, "Current price", 100, Holding::getCurrentPrice);
        Ui.money(table, "Market value", 110, Holding::marketValue);
        Ui.money(table, "Invested value", 110, Holding::getCostBasis);
        Ui.signed(table, "Unrealised P/L", 110, Holding::profitLoss, Formats::signedMoney);
        Ui.signed(table, "Return %", 80, Holding::returnPercent, Formats::signedPercent);
        for (Button b : List.of(analyseButton, buyButton, sellButton)) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> new Data(portfolios.getPortfolio(id), portfolios.history(id)), this::render);
    }

    private void render(Data d) {
        Portfolio p = d.portfolio();
        Ui.metrics(metrics, Ui.money("Available cash", p.cash()), Ui.money("Holdings value", p.holdingsValue()),
                Ui.money("Invested capital", p.invested()), Ui.profit("Realised P/L", p.realized()),
                Ui.profit("Unrealised P/L", p.unrealized()), Ui.profit("Overall P/L", p.totalProfit()),
                Ui.change("Return on starting capital", p.returnPercent()));
        performers.getChildren().setAll(performer("Best-performing holding", p.best().orElse(null)),
                performer("Worst-performing holding", p.worst().orElse(null)));
        // Holdings are rebuilt on each refresh, so the selection is restored by symbol.
        Holding selected = table.getSelectionModel().getSelectedItem();
        table.getSelectionModel().clearSelection();
        Ui.fill(table, p.holdings());
        if (selected != null) {
            table.getItems().stream().filter(h -> h.getSymbol().equals(selected.getSymbol())).findFirst()
                    .ifPresent(h -> table.getSelectionModel().select(h));
        }
        Charts.lines(historyChart, "Value (₹)", d.history().stream().map(PortfolioSnapshot::date).toList(),
                new Charts.Line("Total value", d.history().stream().map(s -> s.totalValue().doubleValue()).toList()),
                new Charts.Line("Holdings", d.history().stream().map(s -> s.holdingsValue().doubleValue()).toList()),
                new Charts.Line("Cash", d.history().stream().map(s -> s.cash().doubleValue()).toList()));
        Charts.pie(stockPie, RiskService.allocation(p.holdings(), false), "No holdings to allocate.");
        Charts.pie(sectorPie, RiskService.allocation(p.holdings(), true), "No holdings to allocate.");
    }

    private javafx.scene.layout.VBox performer(String caption, Holding h) {
        return h == null ? Ui.metric(caption, Formats.NONE, null, "No holdings yet.")
                : Ui.metric(caption, h.getSymbol() + "  " + Formats.signedPercent(h.returnPercent()),
                        Formats.signClass(h.profitLoss()), Formats.signedMoney(h.profitLoss()) + " unrealised");
    }

    private Holding selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    @FXML private void analyse() {
        shell.openStock(selected().getStock().getId());
    }

    @FXML private void buy() {
        TradeDialog.open(shell, selected().getStock().getId(), TradeType.BUY);
    }

    @FXML private void sell() {
        TradeDialog.open(shell, selected().getStock().getId(), TradeType.SELL);
    }

    @FXML private void export() {
        int id = userId();
        shell.export(() -> reports.exportPortfolio(id));
    }
}
