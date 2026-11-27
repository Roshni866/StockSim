package com.stocksim.controller;

import com.stocksim.model.*;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;

import java.util.*;

public class MarketController extends PageController {
    private static final String ALL = "All sectors";
    @FXML private TextField searchField;
    @FXML private ComboBox<String> sectorBox;
    @FXML private ComboBox<String> sortBox;
    @FXML private CheckBox watchedBox;
    @FXML private TableView<Quote> table;
    @FXML private Label nameLabel;
    @FXML private Label priceLabel;
    @FXML private GridPane detailGrid;
    @FXML private StackPane miniChart;
    @FXML private Button watchButton;
    @FXML private Button analyseButton;
    @FXML private Button buyButton;
    @FXML private Button sellButton;

    private final MarketService market = new MarketService();
    private final WatchlistService watchlist = new WatchlistService();
    private final PortfolioService portfolios = new PortfolioService();
    private List<Quote> quotes = List.of();
    private Set<Integer> watched = Set.of();
    private Map<Integer, Integer> owned = Map.of();
    private boolean filling;

    private static final Map<String, Comparator<Quote>> SORTS = new LinkedHashMap<>();
    static {
        SORTS.put("Symbol A–Z", Comparator.comparing(q -> q.stock().getSymbol()));
        SORTS.put("Company A–Z", Comparator.comparing(q -> q.stock().getName()));
        SORTS.put("Price: high to low", Comparator.comparing(Quote::price).reversed());
        SORTS.put("Price: low to high", Comparator.comparing(Quote::price));
        SORTS.put("Change %: best first", Comparator.comparing(Quote::changePercent).reversed());
        SORTS.put("Change %: worst first", Comparator.comparing(Quote::changePercent));
    }

    private record Data(List<Quote> quotes, Set<Integer> watched, Map<Integer, Integer> owned) {
    }

    @Override
    protected void onAttach() {
        Ui.table(table, "No stock matches your search and filters.");
        Ui.text(table, "Company", 170, q -> q.stock().getName());
        Ui.text(table, "Symbol", 108, q -> q.stock().getSymbol());
        Ui.text(table, "Sector", 75, q -> q.stock().getSector());
        Ui.money(table, "Price", 95, Quote::price);
        Ui.signed(table, "Change", 80, Quote::change, Formats::signedMoney);
        Ui.signed(table, "Change %", 75, Quote::changePercent, Formats::signedPercent);
        // On narrow windows the 52-week range is left to the detail panel.
        for (var column : List.of(Ui.money(table, "52W high", 95, Quote::high52),
                Ui.money(table, "52W low", 95, Quote::low52))) {
            column.visibleProperty().bind(table.widthProperty().greaterThan(720));
        }
        sortBox.getItems().setAll(SORTS.keySet());
        sortBox.getSelectionModel().selectFirst();
        sectorBox.getItems().setAll(ALL);
        sectorBox.getSelectionModel().selectFirst();
        searchField.textProperty().addListener((o, a, b) -> filter());
        sectorBox.valueProperty().addListener((o, a, b) -> filter());
        sortBox.valueProperty().addListener((o, a, b) -> {
            table.getSortOrder().clear();
            filter();
        });
        watchedBox.selectedProperty().addListener((o, a, b) -> filter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!filling) {
                showSelected();
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                analyse();
            }
        });
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> {
            Map<Integer, Integer> shares = new HashMap<>();
            portfolios.getPortfolio(id).holdings()
                    .forEach(h -> shares.put(h.getStock().getId(), h.getQuantity()));
            return new Data(market.quotes(id), watchlist.stockIds(id), shares);
        }, d -> {
            quotes = d.quotes();
            watched = d.watched();
            owned = d.owned();
            if (sectorBox.getItems().size() == 1) {
                sectorBox.getItems().addAll(quotes.stream().map(q -> q.stock().getSector()).distinct().sorted()
                        .toList());
            }
            filter();
        });
    }

    private void filter() {
        Quote selected = table.getSelectionModel().getSelectedItem();
        String term = searchField.getText().trim().toLowerCase();
        String sector = sectorBox.getValue();
        List<Quote> shown = quotes.stream()
                .filter(q -> q.stock().describe().toLowerCase().contains(term))
                .filter(q -> ALL.equals(sector) || q.stock().getSector().equals(sector))
                .filter(q -> !watchedBox.isSelected() || watched.contains(q.stock().getId()))
                .sorted(SORTS.get(sortBox.getValue()))
                .toList();
        filling = true;
        table.getItems().setAll(shown);
        table.sort();
        // Quotes are new objects after a refresh, so reselect by stock.
        table.getItems().stream()
                .filter(q -> selected != null && q.stock().equals(selected.stock())).findFirst()
                .ifPresentOrElse(q -> table.getSelectionModel().select(q),
                        () -> table.getSelectionModel().selectFirst());
        filling = false;
        showSelected();
    }

    private void showSelected() {
        Quote q = table.getSelectionModel().getSelectedItem();
        for (Button b : List.of(watchButton, analyseButton, buyButton, sellButton)) {
            b.setDisable(q == null);
        }
        detailGrid.getChildren().clear();
        if (q == null) {
            nameLabel.setText("Select a stock");
            priceLabel.setText("");
            Charts.empty(miniChart, "No stock selected.");
            return;
        }
        int id = q.stock().getId();
        int shares = owned.getOrDefault(id, 0);
        nameLabel.setText(q.stock().describe());
        priceLabel.setText(Formats.money(q.price()) + "   " + Formats.signedPercent(q.changePercent()));
        priceLabel.getStyleClass().setAll("label", "big-price", Formats.signClass(q.changePercent()));
        Ui.row(detailGrid, "Sector", q.stock().getSector());
        Ui.row(detailGrid, "Day change", Formats.signedMoney(q.change()), Formats.signClass(q.change()));
        Ui.row(detailGrid, "52-week range", Formats.money(q.low52()) + " – " + Formats.money(q.high52()));
        Ui.row(detailGrid, "Shares owned", String.valueOf(shares));
        sellButton.setDisable(shares == 0);
        watchButton.setText(watched.contains(id) ? "★ Remove from watchlist" : "☆ Add to watchlist");
        int user = userId();
        shell.load(() -> market.series(user, id, AnalysisService.Period.ONE_MONTH.points()), series -> {
            Quote now = table.getSelectionModel().getSelectedItem();
            if (now != null && now.stock().getId() == id) {
                Charts.lines(miniChart, null, series.stream().map(StockPrice::date).toList(), new Charts.Line(
                        "1 month", series.stream().map(s -> s.price().doubleValue()).toList()));
            }
        });
    }

    @FXML private void toggleWatch() {
        Quote q = table.getSelectionModel().getSelectedItem();
        if (q == null) {
            return;
        }
        int user = userId();
        int id = q.stock().getId();
        boolean remove = watched.contains(id);
        shell.act(() -> {
            if (remove) {
                watchlist.remove(user, id);
            } else {
                watchlist.add(user, id);
            }
        }, q.stock().getSymbol() + (remove ? " removed from" : " added to") + " your watchlist.");
    }

    @FXML private void analyse() {
        Quote q = table.getSelectionModel().getSelectedItem();
        if (q != null) {
            shell.openStock(q.stock().getId());
        }
    }

    @FXML private void buy() {
        trade(TradeType.BUY);
    }

    @FXML private void sell() {
        trade(TradeType.SELL);
    }

    private void trade(TradeType type) {
        Quote q = table.getSelectionModel().getSelectedItem();
        if (q != null) {
            TradeDialog.open(shell, q.stock().getId(), type);
        }
    }
}
