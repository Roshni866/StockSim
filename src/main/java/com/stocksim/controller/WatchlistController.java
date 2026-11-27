package com.stocksim.controller;

import com.stocksim.model.Quote;
import com.stocksim.model.Stock;
import com.stocksim.model.TradeType;
import com.stocksim.service.MarketService;
import com.stocksim.service.WatchlistService;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;

import java.util.List;

public class WatchlistController extends PageController {
    @FXML private ComboBox<Stock> addBox;
    @FXML private Button addButton;
    @FXML private TableView<Quote> table;
    @FXML private Button detailsButton;
    @FXML private Button alertButton;
    @FXML private Button buyButton;
    @FXML private Button removeButton;
    private final WatchlistService watchlist = new WatchlistService();
    private final MarketService market = new MarketService();

    private record Data(List<Quote> watched, List<Stock> others) {
    }

    @Override
    protected void onAttach() {
        Ui.table(table, "Your watchlist is empty. Pick a stock above and press Add.");
        Ui.text(table, "Symbol", 100, q -> q.stock().getSymbol());
        Ui.text(table, "Company", 180, q -> q.stock().getName());
        Ui.text(table, "Sector", 80, q -> q.stock().getSector());
        Ui.money(table, "Simulated price", 110, Quote::price);
        Ui.signed(table, "Day change", 90, Quote::change, Formats::signedMoney);
        Ui.signed(table, "Day %", 75, Quote::changePercent, Formats::signedPercent);
        for (Button b : List.of(detailsButton, alertButton, buyButton, removeButton)) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
        addButton.disableProperty().bind(addBox.valueProperty().isNull());
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> {
            List<Quote> watched = watchlist.quotes(id);
            return new Data(watched, market.stocks().stream()
                    .filter(s -> watched.stream().noneMatch(q -> q.stock().equals(s))).toList());
        }, d -> {
            Quote selected = table.getSelectionModel().getSelectedItem();
            table.getSelectionModel().clearSelection();
            Ui.fill(table, d.watched());
            if (selected != null) {
                table.getItems().stream().filter(q -> q.stock().equals(selected.stock())).findFirst()
                        .ifPresent(q -> table.getSelectionModel().select(q));
            }
            addBox.getItems().setAll(d.others());
            addBox.setValue(d.others().isEmpty() ? null : d.others().get(0));
        });
    }

    private int selectedId() {
        return table.getSelectionModel().getSelectedItem().stock().getId();
    }

    @FXML private void add() {
        int user = userId();
        Stock stock = addBox.getValue();
        shell.act(() -> watchlist.add(user, stock.getId()), stock.getSymbol() + " added.");
    }

    @FXML private void remove() {
        int user = userId();
        int id = selectedId();
        shell.act(() -> watchlist.remove(user, id), "Removed from your watchlist.");
    }

    @FXML private void details() {
        shell.openStock(selectedId());
    }

    @FXML private void buy() {
        TradeDialog.open(shell, selectedId(), TradeType.BUY);
    }

    @FXML private void createAlert() {
        GoalsController goals = shell.controller(Page.GOALS);
        goals.prepareAlert(selectedId());
        shell.open(Page.GOALS);
    }
}
