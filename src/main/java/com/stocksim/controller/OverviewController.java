package com.stocksim.controller;

import com.stocksim.model.*;
import com.stocksim.service.*;
import com.stocksim.ui.Charts;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OverviewController extends PageController {
    private static final int CHART_DAYS = 60;
    @FXML private HBox controlsBox;
    @FXML private Pane metrics;
    @FXML private StackPane equityChart;
    @FXML private StackPane returnsChart;
    @FXML private StackPane indexChart;
    @FXML private StackPane sectorChart;
    @FXML private Label directionLabel;
    @FXML private Label watchLabel;
    @FXML private TableView<Quote> watchTable;
    @FXML private TableView<Transaction> tradeTable;
    @FXML private VBox eventsBox;
    @FXML private StackPane sectorPie;
    @FXML private StackPane stockPie;

    private final PortfolioService portfolios = new PortfolioService();
    private final MarketService market = new MarketService();
    private final WatchlistService watchlist = new WatchlistService();
    private final SimulationService simulation = new SimulationService();

    private record Data(Portfolio portfolio, List<PortfolioSnapshot> history, List<MarketService.IndexPoint> index,
                        List<Quote> quotes, java.util.Set<Integer> watched, List<Transaction> trades,
                        List<MarketEvent> events, SimulationState state) {
    }

    @Override
    protected void onAttach() {
        controlsBox.getChildren().add(shell.timeControls());
        Ui.table(watchTable, "No stocks available.");
        Ui.text(watchTable, "Symbol", 90, q -> q.stock().getSymbol());
        Ui.text(watchTable, "Sector", 80, q -> q.stock().getSector());
        Ui.money(watchTable, "Price", 100, Quote::price);
        Ui.signed(watchTable, "Day %", 70, Quote::changePercent, Formats::signedPercent);
        watchTable.setOnMouseClicked(e -> {
            Quote q = watchTable.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && q != null) {
                shell.openStock(q.stock().getId());
            }
        });
        Ui.table(tradeTable, "No trades yet. Open the Market Terminal to buy your first shares.");
        Ui.number(tradeTable, "Day", 50, Transaction::simulationDay, String::valueOf);
        Ui.text(tradeTable, "Symbol", 90, Transaction::symbol);
        Ui.column(tradeTable, "Type", 60, t -> t.type().name(), v -> v,
                v -> "BUY".equals(v) ? "positive" : "negative", javafx.geometry.Pos.CENTER);
        Ui.number(tradeTable, "Qty", 55, Transaction::quantity, String::valueOf);
        Ui.money(tradeTable, "Total", 110, Transaction::total);
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> new Data(portfolios.getPortfolio(id), portfolios.history(id), market.index(id, CHART_DAYS),
                market.quotes(id), watchlist.stockIds(id), portfolios.transactions(id), market.recentEvents(id, 6),
                simulation.state(id)), this::render);
    }

    private void render(Data d) {
        Portfolio p = d.portfolio();
        Ui.metrics(metrics, Ui.money("Available cash", p.cash()), Ui.money("Total invested", p.invested()),
                Ui.metric("Portfolio value", Formats.money(p.totalValue()), "accent",
                        "Cash + holdings " + Formats.money(p.holdingsValue())),
                Ui.profit("Total profit / loss", p.totalProfit()), Ui.change("Total return", p.returnPercent()),
                Ui.metric("Simulation day", String.valueOf(d.state().currentDay()), null,
                        (d.state().currentDay() - d.state().startDay()) + " days since you started"));

        List<PortfolioSnapshot> h = d.history();
        Charts.lines(equityChart, "Value (₹)", h.stream().map(PortfolioSnapshot::date).toList(),
                new Charts.Line("Portfolio value",
                        h.stream().map(s -> s.totalValue().doubleValue()).toList()));
        Map<String, BigDecimal> daily = new LinkedHashMap<>();
        for (int i = Math.max(1, h.size() - 15); i < h.size(); i++) {
            daily.put(String.valueOf(h.get(i).day()),
                    Money.change(h.get(i - 1).totalValue(), h.get(i).totalValue()));
        }
        Charts.signedBars(returnsChart, "%", daily, "Daily returns appear after you advance time.");

        List<MarketService.IndexPoint> index = d.index();
        Charts.lines(indexChart, "Index", index.stream().map(MarketService.IndexPoint::date).toList(),
                new Charts.Line("Simulated index",
                        index.stream().map(MarketService.IndexPoint::level).toList()));
        long up = d.quotes().stream().filter(q -> q.changePercent().signum() > 0).count();
        long down = d.quotes().stream().filter(q -> q.changePercent().signum() < 0).count();
        double last = index.get(index.size() - 1).level();
        double before = index.size() > 1 ? index.get(index.size() - 2).level() : last;
        BigDecimal move = Money.of(before == 0 ? 0 : (last / before - 1) * 100);
        directionLabel.setText("Equal-weighted index " + Formats.signedPercent(move) + " today  •  "
                + (move.signum() > 0 ? "Rising" : move.signum() < 0 ? "Falling" : "Flat") + "  •  " + up
                + " up, " + down + " down");
        directionLabel.getStyleClass().setAll("label", Formats.signClass(move));
        Charts.signedBars(sectorChart, "Day %", market.sectorPerformance(d.quotes()), "No sector data.");

        List<Quote> watched = d.quotes().stream().filter(q -> d.watched().contains(q.stock().getId())).toList();
        watchLabel.setText(watched.isEmpty()
                ? "Your watchlist is empty, so all stocks are shown. Double-click for analysis."
                : "Your watchlist. Double-click a row for analysis.");
        Ui.fill(watchTable, watched.isEmpty() ? d.quotes() : watched);

        List<Transaction> recent = new ArrayList<>(d.trades());
        Collections.reverse(recent);
        Ui.fill(tradeTable, recent.stream().limit(8).toList());

        eventsBox.getChildren().clear();
        if (d.events().isEmpty()) {
            eventsBox.getChildren().add(Ui.note("No simulated events yet. Events appear as time advances."));
        }
        for (MarketEvent e : d.events()) {
            Label head = Ui.note("Day " + e.day() + "  •  " + (e.sector().equals("ALL") ? "All sectors"
                    : e.sector()) + "  •  " + (e.fromScenario() ? "Scenario rule " : "Price effect ")
                    + Formats.signedPercent(e.impactPercent()) + (e.fromScenario() ? " per day" : ""), "event-meta");
            VBox item = new VBox(1, Ui.note(e.title(), "event-title"), head, Ui.note(e.description()));
            item.getStyleClass().add("event");
            eventsBox.getChildren().add(item);
        }
        Charts.pie(sectorPie, RiskService.allocation(p.holdings(), true), "Buy shares to see sector allocation.");
        Charts.pie(stockPie, RiskService.allocation(p.holdings(), false), "Buy shares to see stock allocation.");
    }
}
