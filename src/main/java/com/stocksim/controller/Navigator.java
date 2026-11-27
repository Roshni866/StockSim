package com.stocksim.controller;

import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Swaps the single window between the sign-in screens and the application shell. */
public final class Navigator {
    private static Stage stage;
    private static ShellController shell;

    private Navigator() {
    }

    public static void start(Stage primary, double width, double height) {
        stage = primary;
        Dialogs.setOwner(primary);
        Scene scene = new Scene(new javafx.scene.layout.StackPane(), width, height);
        scene.getStylesheets().add(Ui.stylesheet());
        primary.setScene(scene);
        show("welcome");
    }

    public static FXMLLoader loader(String name) {
        return new FXMLLoader(Navigator.class.getResource("/com/stocksim/fxml/" + name + ".fxml"));
    }

    public static <T> T load(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + loader.getLocation(), e);
        }
    }

    /** Shows a full-window screen and returns its controller. */
    public static <T> T show(String name) {
        FXMLLoader loader = loader(name);
        Parent root = load(loader);
        stage.getScene().setRoot(root);
        return loader.getController();
    }

    public static ShellController openShell() {
        shell = show("shell");
        shell.open(Page.OVERVIEW);
        return shell;
    }

    /** The shell of the current session, or null when signed out. */
    public static ShellController shell() {
        return shell;
    }

    public static void logout() {
        com.stocksim.util.UserSession.getInstance().logout();
        shell = null;
        show("welcome");
    }
}
