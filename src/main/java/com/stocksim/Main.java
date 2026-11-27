package com.stocksim;

import com.stocksim.controller.Navigator;
import com.stocksim.ui.Dialogs;
import com.stocksim.util.DataSeeder;
import com.stocksim.util.SchemaMigrator;
import com.stocksim.util.UserSession;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Starts StockSim: prepares the database, then shows the window. */
public class Main extends Application {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private String startupProblem;

    @Override
    public void init() {
        configureLogging();
        try {
            SchemaMigrator.migrate();
            DataSeeder.seedIfEmpty();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Startup failed", e);
            startupProblem = e.getMessage();
        }
    }

    @Override
    public void start(Stage stage) {
        if (startupProblem != null) {
            Dialogs.error("StockSim cannot reach its database", """
                    Check that:
                      1. The MySQL server is running
                      2. db.user and db.password are set in stocksim.local.properties

                    Details:\s""" + startupProblem);
            Platform.exit();
            return;
        }
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Navigator.start(stage, Math.min(1500, screen.getWidth() * 0.95), Math.min(940, screen.getHeight() * 0.95));
        stage.setTitle("StockSim — Market Simulation Workspace");
        stage.setMinWidth(Math.min(1100, screen.getWidth()));
        stage.setMinHeight(Math.min(700, screen.getHeight()));
        stage.show();
    }

    @Override
    public void stop() {
        UserSession.getInstance().logout();
        LOGGER.info("StockSim closed.");
    }

    private static void configureLogging() {
        try {
            Files.createDirectories(Path.of("logs"));
            FileHandler handler = new FileHandler("logs/stocksim.log", 1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("com.stocksim").addHandler(handler);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "File logging is unavailable", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
