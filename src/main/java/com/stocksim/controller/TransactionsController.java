package com.stocksim.controller;

import com.stocksim.model.Transaction;
import com.stocksim.service.PortfolioService;
import com.stocksim.service.ReportService;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TransactionsController extends PageController {
    @FXML private ComboBox<String> typeBox;
    @FXML private TextField searchField;
    @FXML private Label countLabel;
    @FXML private TableView<Transaction> table;
    @FXML private GridPane detailGrid;
    private final PortfolioService portfolios = new PortfolioService();
    private final ReportService reports = new ReportService();
    private List<Transaction> all = List.of();

    @Override
    protected void onAttach() {
        typeBox.getItems().setAll("All", "BUY", "SELL");
        typeBox.setValue("All");
        typeBox.valueProperty().addListener((o, a, b) -> filter());
        searchField.textProperty().addListener((o, a, b) -> filter());
        Ui.table(table, "No transactions match. Trades you place appear here.");
        Ui.number(table, "ID", 60, Transaction::id, String::valueOf);
        Ui.number(table, "Market date", 100, Transaction::marketDate, Formats::date);
        Ui.number(table, "Sim day", 65, Transaction::simulationDay, String::valueOf);
        Ui.text(table, "Stock", 95, Transaction::symbol);
        Ui.column(table, "Type", 55, t -> t.type().name(), v -> v,
                v -> "BUY".equals(v) ? "positive" : "negative", javafx.geometry.Pos.CENTER);
        Ui.number(table, "Quantity", 70, Transaction::quantity, String::valueOf);
        Ui.money(table, "Execution price", 110, Transaction::price);
        Ui.money(table, "Total amount", 120, Transaction::total);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> detail(b));
        detail(null);
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> portfolios.transactions(id), list -> {
            all = new ArrayList<>(list);
            Collections.reverse(all);
            filter();
        });
    }

    private void filter() {
        String type = typeBox.getValue();
        String term = searchField.getText().trim().toLowerCase();
        List<Transaction> shown = all.stream()
                .filter(t -> "All".equals(type) || t.type().name().equals(type))
                .filter(t -> (t.symbol() + " " + t.companyName() + " " + t.id() + " "
                        + Formats.date(t.marketDate())).toLowerCase().contains(term))
                .toList();
        Ui.fill(table, shown);
        countLabel.setText(shown.size() + " of " + all.size() + " transactions");
    }

    private void detail(Transaction t) {
        detailGrid.getChildren().clear();
        if (t == null) {
            detailGrid.add(Ui.label("Select a transaction to inspect it.", "muted"), 0, 0);
            return;
        }
        Ui.row(detailGrid, "Transaction ID", String.valueOf(t.id()));
        Ui.row(detailGrid, "Company", t.companyName() + " (" + t.symbol() + ")");
        Ui.row(detailGrid, "Type", t.type().name());
        Ui.row(detailGrid, "Quantity", String.valueOf(t.quantity()));
        Ui.row(detailGrid, "Execution price", Formats.money(t.price()));
        Ui.row(detailGrid, "Total amount", Formats.money(t.total()));
        Ui.row(detailGrid, "Realised P/L", Formats.signedMoney(t.realizedProfit()),
                Formats.signClass(t.realizedProfit()));
        Ui.row(detailGrid, "Simulation day", String.valueOf(t.simulationDay()));
        Ui.row(detailGrid, "Market date", Formats.date(t.marketDate()));
        Ui.row(detailGrid, "Recorded", Formats.dateTime(t.createdAt()));
    }

    @FXML private void export() {
        int id = userId();
        shell.export(() -> reports.exportTransactions(id));
    }
}
