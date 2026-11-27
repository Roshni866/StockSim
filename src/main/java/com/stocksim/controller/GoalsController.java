package com.stocksim.controller;

import com.stocksim.exception.ValidationException;
import com.stocksim.model.*;
import com.stocksim.service.*;
import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Validator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.math.BigDecimal;
import java.util.List;

public class GoalsController extends PageController {
    @FXML private TabPane tabs;
    @FXML private TextField goalName;
    @FXML private TextField goalTarget;
    @FXML private Label goalError;
    @FXML private Button updateButton;
    @FXML private Button deleteGoalButton;
    @FXML private TableView<InvestmentGoal> goalTable;
    @FXML private ComboBox<Stock> alertStock;
    @FXML private ComboBox<PriceAlert.Direction> alertDirection;
    @FXML private TextField alertTarget;
    @FXML private Label alertPrice;
    @FXML private Label alertError;
    @FXML private TableView<PriceAlert> activeTable;
    @FXML private TableView<PriceAlert> triggeredTable;

    private final GoalService goals = new GoalService();
    private final AlertService alerts = new AlertService();
    private final MarketService market = new MarketService();
    private List<Quote> quotes = List.of();
    private Integer wantedStock;

    private record Data(List<InvestmentGoal> goals, List<PriceAlert> alerts, List<Quote> quotes) {
    }

    @Override
    protected void onAttach() {
        Ui.table(goalTable, "No goals yet. For example: reach ₹1,25,000.");
        Ui.text(goalTable, "Goal", 150, InvestmentGoal::name);
        Ui.money(goalTable, "Target", 110, InvestmentGoal::target);
        Ui.money(goalTable, "Current value", 110, InvestmentGoal::currentValue);
        TableColumn<InvestmentGoal, BigDecimal> progress = new TableColumn<>("Progress");
        progress.setPrefWidth(180);
        progress.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().progressPercent()));
        progress.setCellFactory(c -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();

            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    bar.setProgress(Math.min(1, value.doubleValue() / 100));
                    bar.setPrefWidth(100);
                    setGraphic(bar);
                    setGraphicTextGap(10);
                    setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    setText(Formats.percent(value));
                }
            }
        });
        goalTable.getColumns().add(progress);
        Ui.number(goalTable, "Created", 100, g -> g.createdAt().toLocalDate(), Formats::date);
        Ui.text(goalTable, "Status", 90, g -> g.achieved() ? "Achieved" : "In progress");
        goalTable.getSelectionModel().selectedItemProperty().addListener((o, a, g) -> {
            if (g != null) {
                goalName.setText(g.name());
                goalTarget.setText(g.target().toPlainString());
            }
        });
        updateButton.disableProperty().bind(goalTable.getSelectionModel().selectedItemProperty().isNull());
        deleteGoalButton.disableProperty().bind(goalTable.getSelectionModel().selectedItemProperty().isNull());

        alertDirection.getItems().setAll(PriceAlert.Direction.values());
        alertDirection.setValue(PriceAlert.Direction.ABOVE);
        alertStock.valueProperty().addListener((o, a, b) -> showPrice());
        Ui.table(activeTable, "No active alerts.");
        Ui.text(activeTable, "Stock", 100, PriceAlert::symbol);
        Ui.text(activeTable, "Condition", 90, a -> "Price " + a.direction().name().toLowerCase());
        Ui.money(activeTable, "Target price", 110, PriceAlert::target);
        Ui.table(triggeredTable, "No alerts have triggered yet.");
        Ui.text(triggeredTable, "Stock", 100, PriceAlert::symbol);
        Ui.text(triggeredTable, "Condition", 90, a -> "Price " + a.direction().name().toLowerCase());
        Ui.money(triggeredTable, "Target", 100, PriceAlert::target);
        Ui.number(triggeredTable, "Sim day", 70, PriceAlert::triggeredDay, String::valueOf);
        Ui.number(triggeredTable, "Date", 100, PriceAlert::triggeredDate, Formats::date);
        Ui.money(triggeredTable, "Triggered price", 110, PriceAlert::triggeredPrice);
    }

    /** Opens the alerts tab with a stock already chosen. */
    void prepareAlert(int stockId) {
        wantedStock = stockId;
        tabs.getSelectionModel().select(1);
    }

    @Override
    public void refresh() {
        int id = userId();
        shell.load(() -> new Data(goals.list(id), alerts.list(id), market.quotes(id)), d -> {
            quotes = d.quotes();
            goalTable.getSelectionModel().clearSelection();
            Ui.fill(goalTable, d.goals());
            Integer keep = wantedStock != null ? wantedStock
                    : alertStock.getValue() == null ? null : alertStock.getValue().getId();
            alertStock.getItems().setAll(quotes.stream().map(Quote::stock).toList());
            alertStock.getItems().stream().filter(s -> Integer.valueOf(s.getId()).equals(keep)).findFirst()
                    .ifPresentOrElse(alertStock::setValue, () -> alertStock.getSelectionModel().selectFirst());
            wantedStock = null;
            showPrice();
            Ui.fill(activeTable, d.alerts().stream().filter(a -> !a.triggered()).toList());
            Ui.fill(triggeredTable, d.alerts().stream().filter(PriceAlert::triggered).toList());
        });
    }

    private void showPrice() {
        Stock stock = alertStock.getValue();
        alertPrice.setText(quotes.stream().filter(q -> q.stock().equals(stock)).findFirst()
                .map(q -> "Current simulated price: " + Formats.money(q.price())).orElse(""));
    }

    @FXML private void createGoal() {
        saveGoal(null);
    }

    @FXML private void updateGoal() {
        saveGoal(goalTable.getSelectionModel().getSelectedItem());
    }

    private void saveGoal(InvestmentGoal existing) {
        goalError.setText("");
        try {
            String name = goalName.getText();
            if (!Validator.isNotEmpty(name)) {
                throw new ValidationException("Give the goal a name.");
            }
            BigDecimal target = Validator.positiveAmount(goalTarget.getText(), "Target amount");
            int id = userId();
            shell.act(() -> {
                if (existing == null) {
                    goals.create(id, name, target);
                } else {
                    goals.update(id, existing.id(), name, target);
                }
            }, existing == null ? "Goal created." : "Goal updated.");
            goalName.clear();
            goalTarget.clear();
        } catch (ValidationException e) {
            goalError.setText(e.getMessage());
        }
    }

    @FXML private void deleteGoal() {
        InvestmentGoal g = goalTable.getSelectionModel().getSelectedItem();
        if (Dialogs.confirm("Delete the goal \"" + g.name() + "\"?", "This cannot be undone.")) {
            int id = userId();
            shell.act(() -> goals.delete(id, g.id()), "Goal deleted.");
            goalName.clear();
            goalTarget.clear();
        }
    }

    @FXML private void createAlert() {
        alertError.setText("");
        try {
            Stock stock = alertStock.getValue();
            if (stock == null) {
                throw new ValidationException("Choose a stock.");
            }
            PriceAlert.Direction direction = alertDirection.getValue();
            BigDecimal target = Validator.positiveAmount(alertTarget.getText(), "Target price");
            int id = userId();
            shell.act(() -> alerts.create(id, stock.getId(), direction, target),
                    "Alert saved. It is checked each time simulation time advances.");
            alertTarget.clear();
        } catch (ValidationException e) {
            alertError.setText(e.getMessage());
        }
    }

    @FXML private void deleteActive() {
        delete(activeTable.getSelectionModel().getSelectedItem());
    }

    @FXML private void deleteTriggered() {
        delete(triggeredTable.getSelectionModel().getSelectedItem());
    }

    private void delete(PriceAlert alert) {
        if (alert == null) {
            alertError.setText("Select an alert to delete.");
        } else if (Dialogs.confirm("Delete this " + alert.symbol() + " alert?", "This cannot be undone.")) {
            int id = userId();
            shell.act(() -> alerts.delete(id, alert.id()), "Alert deleted.");
        }
    }
}
