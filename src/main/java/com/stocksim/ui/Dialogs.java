package com.stocksim.ui;

import com.stocksim.exception.AuthenticationException;
import com.stocksim.exception.TradeException;
import com.stocksim.exception.ValidationException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Styled alerts and confirmations. */
public final class Dialogs {
    private static final Logger LOGGER = Logger.getLogger(Dialogs.class.getName());
    private static Window owner;
    private static BiPredicate<String, String> confirmer = Dialogs::askUser;

    private Dialogs() {
    }

    public static void setOwner(Window window) {
        owner = window;
    }

    /** Replaces the confirmation prompt; automated UI tests use this. */
    public static void setConfirmer(BiPredicate<String, String> replacement) {
        confirmer = replacement == null ? Dialogs::askUser : replacement;
    }

    public static void style(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(Ui.stylesheet());
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
    }

    public static boolean confirm(String heading, String detail) {
        return confirmer.test(heading, detail);
    }

    /** The message to show a user for a failure; unexpected errors are also logged. */
    public static String message(Throwable error) {
        if (error instanceof ValidationException || error instanceof TradeException
                || error instanceof AuthenticationException) {
            return error.getMessage();
        }
        LOGGER.log(Level.SEVERE, "Unexpected error", error);
        return "Something went wrong: " + error.getMessage();
    }

    public static void error(String heading, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR, detail);
        alert.setTitle("StockSim");
        alert.setHeaderText(heading);
        style(alert);
        alert.showAndWait();
    }

    private static boolean askUser(String heading, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("StockSim");
        alert.setHeaderText(heading);
        style(alert);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
