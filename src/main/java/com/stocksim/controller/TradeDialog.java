package com.stocksim.controller;

import com.stocksim.exception.ValidationException;
import com.stocksim.model.Portfolio;
import com.stocksim.model.Quote;
import com.stocksim.model.TradeType;
import com.stocksim.model.Transaction;
import com.stocksim.service.MarketService;
import com.stocksim.service.PortfolioService;
import com.stocksim.service.SettingsService;
import com.stocksim.service.TradingService;
import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;
import com.stocksim.util.UserSession;
import com.stocksim.util.Validator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The order ticket used by every Buy and Sell button. */
final class TradeDialog {

    private record Ticket(Quote quote, BigDecimal cash, int owned, boolean confirm) {
    }

    private TradeDialog() {
    }

    static void open(ShellController shell, int stockId, TradeType type) {
        int userId = UserSession.getInstance().requireUserId();
        shell.load(() -> {
            Portfolio p = new PortfolioService().getPortfolio(userId);
            Quote quote = new MarketService().quotes(userId).stream().filter(q -> q.stock().getId() == stockId)
                    .findFirst().orElseThrow(() -> new ValidationException("That stock does not exist."));
            int owned = p.holdings().stream().filter(h -> h.getStock().getId() == stockId)
                    .mapToInt(h -> h.getQuantity()).findFirst().orElse(0);
            return new Ticket(quote, p.cash(), owned, new SettingsService().get(userId).confirmTrades());
        }, ticket -> show(shell, userId, type, ticket));
    }

    private static void show(ShellController shell, int userId, TradeType type, Ticket t) {
        boolean buy = type == TradeType.BUY;
        String symbol = t.quote().stock().getSymbol();
        BigDecimal price = t.quote().price();
        if (!buy && t.owned() == 0) {
            shell.toast("Nothing to sell", "You do not own any " + symbol + " shares.", "toast-warning");
            return;
        }
        int limit = buy ? t.cash().divide(price, 0, RoundingMode.DOWN).intValue() : t.owned();

        Dialog<Integer> dialog = new Dialog<>();
        dialog.setTitle(type + " " + symbol);
        dialog.setHeaderText(type + "  " + t.quote().stock().describe());
        Dialogs.style(dialog);
        ButtonType confirm = new ButtonType("Confirm " + type, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);

        TextField quantity = new TextField();
        quantity.setPromptText("Number of shares");
        Label total = Ui.label(Formats.NONE);
        Label after = Ui.label(Formats.NONE);
        Label error = Ui.label("", "error-label");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        Ui.row(grid, "Simulated price", Formats.money(price));
        Ui.row(grid, "Available cash", Formats.money(t.cash()));
        Ui.row(grid, "Shares owned", String.valueOf(t.owned()));
        Ui.row(grid, buy ? "Maximum you can buy" : "Maximum you can sell", String.valueOf(limit));
        grid.addRow(4, Ui.label("Quantity", "muted"), quantity);
        grid.addRow(5, Ui.label(buy ? "Purchase cost" : "Sale proceeds", "muted"), total);
        grid.addRow(6, Ui.label("Cash after trade", "muted"), after);
        VBox box = new VBox(8, grid, error);
        box.setMinWidth(380);
        dialog.getDialogPane().setContent(box);

        Node button = dialog.getDialogPane().lookupButton(confirm);
        button.setDisable(true);
        quantity.textProperty().addListener((o, old, text) -> {
            button.setDisable(true);
            total.setText(Formats.NONE);
            after.setText(Formats.NONE);
            error.setText("");
            if (text.isBlank()) {
                return;
            }
            try {
                int n = Validator.positiveInt(text, "Quantity");
                BigDecimal value = Money.times(price, n);
                total.setText(Formats.money(value));
                after.setText(Formats.money(buy ? t.cash().subtract(value) : t.cash().add(value)));
                if (n > limit) {
                    error.setText(buy ? "Not enough cash for that quantity." : "You own only " + limit + ".");
                } else {
                    button.setDisable(false);
                }
            } catch (ValidationException e) {
                error.setText(e.getMessage());
            }
        });
        dialog.setResultConverter(b -> b == confirm ? Validator.positiveInt(quantity.getText(), "Quantity") : null);
        Platform.runLater(quantity::requestFocus);
        // Shown after the current callback returns, as JavaFX requires for modal dialogs.
        Platform.runLater(() -> dialog.showAndWait().ifPresent(n -> submit(shell, userId, type, t, n)));
    }

    private static void submit(ShellController shell, int userId, TradeType type, Ticket t, int n) {
        String symbol = t.quote().stock().getSymbol();
        if (t.confirm() && !Dialogs.confirm("Place this order?", type + " " + n + " " + symbol + " at "
                + Formats.money(t.quote().price()) + " = " + Formats.money(Money.times(t.quote().price(), n)))) {
            return;
        }
        TradingService trading = new TradingService();
        int stockId = t.quote().stock().getId();
        shell.<Transaction>act(() -> type == TradeType.BUY ? trading.buy(userId, stockId, n)
                : trading.sell(userId, stockId, n), done -> shell.toast("Trade executed", done.type() + " "
                + done.quantity() + " " + done.symbol() + " for " + Formats.money(done.total())
                + (done.realizedProfit() == null ? "" : "\nRealised P/L "
                + Formats.signedMoney(done.realizedProfit())), "toast-success"));
    }
}
