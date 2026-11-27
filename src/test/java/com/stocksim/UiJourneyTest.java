package com.stocksim;

import com.stocksim.controller.Navigator;
import com.stocksim.controller.Page;
import com.stocksim.controller.ShellController;
import com.stocksim.model.PriceAlert;
import com.stocksim.ui.Async;
import com.stocksim.ui.Dialogs;
import com.stocksim.util.UserSession;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the real window from registration to logout by pressing the same buttons a
 * user presses, against the test database. Screenshots are written to
 * target/screenshots. Skipped when no display is available.
 */
class UiJourneyTest {
    private static final Path SHOTS = Path.of("target", "screenshots");
    private static final List<String> PROBLEMS = new ArrayList<>();
    private static Stage stage;

    @BeforeAll
    static void startToolkit() throws Exception {
        boolean headlessLinux = System.getProperty("os.name").startsWith("Linux")
                && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null;
        assumeTrue(!headlessLinux, "No display: run with xvfb-run to include the UI test");
        TestSupport.database();
        Files.createDirectories(SHOTS);
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                boolean css = String.valueOf(record.getLoggerName()).contains("css");
                if (record.getLevel().intValue() >= (css ? Level.WARNING : Level.SEVERE).intValue()) {
                    PROBLEMS.add(record.getLoggerName() + ": " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("").addHandler(capture);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(20, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
        fx(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
                e.printStackTrace();
                PROBLEMS.add(e.toString());
            });
            Dialogs.setConfirmer((heading, detail) -> true);
            stage = new Stage();
            Navigator.start(stage, 1440, 900);
            stage.show();
        });
    }

    @AfterAll
    static void close() throws Exception {
        if (stage != null) {
            fx(() -> {
                Dialogs.setConfirmer(null);
                UserSession.getInstance().logout();
                stage.close();
            });
        }
    }

    @Test
    void completeUserJourneyThroughEveryScreen() throws Exception {
        String username = "ui" + System.nanoTime() % 1_000_000_000;
        shot("01-welcome");

        // Registration, including a rejected attempt.
        press("Create an account");
        type("nameField", "Asha Verma");
        type("usernameField", username);
        type("emailField", username + "@example.com");
        type("passwordField", TestSupport.PASSWORD);
        type("confirmField", "different123");
        press("Create account");
        await(() -> !text("errorLabel").isEmpty(), "mismatch error");
        shot("02-register-error");
        type("confirmField", TestSupport.PASSWORD);
        press("Create account");
        await(() -> find("capitalLabel") != null, "capital screen");
        assertEquals("₹1,00,000.00", fxGet(() -> text("capitalLabel")));
        shot("03-initial-capital");
        press("Open my workspace");
        settle();
        ShellController shell = Navigator.shell();
        assertNotNull(shell);
        int userId = UserSession.getInstance().requireUserId();

        // Empty states for a brand new account.
        for (Page page : Page.values()) {
            open(page);
        }
        open(Page.OVERVIEW);
        shot("04-overview-new-account");

        // Search, filter and buy through the order ticket.
        open(Page.MARKET);
        int stocks = rows("table");
        assertTrue(stocks > 5);
        type("searchField", "zzzz");
        assertEquals(0, rows("table"));
        type("searchField", "");
        select("table", 0);
        buy(20);
        select("table", 3);
        buy(15);
        select("table", 6);
        buy(10);
        assertEquals(3, TestSupport.count("holdings", userId));
        press("☆ Add to watchlist");
        settle();
        assertEquals(1, TestSupport.count("watchlist", userId));
        shot("05-market-terminal");

        // Analysis periods.
        press("Open full analysis");
        settle();
        for (String period : List.of("1D", "1W", "6M", "1M")) {
            fx(() -> toggle(period).fire());
            settle();
        }
        shot("06-stock-analysis");

        // Goals and alerts.
        open(Page.GOALS);
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(0));
        type("goalName", "Reach 1.25 lakh");
        type("goalTarget", "125000");
        press("Create goal");
        settle();
        type("goalName", "Bad goal");
        type("goalTarget", "-5");
        press("Create goal");
        assertFalse(fxGet(() -> text("goalError")).isEmpty());
        assertEquals(1, TestSupport.count("goals", userId));
        shot("07-goals");
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(1));
        settle();
        type("alertTarget", "0.50");
        press("Create alert");
        settle();
        type("alertTarget", "999999");
        press("Create alert");
        settle();

        // Time controls update the status bar and trigger the alert.
        open(Page.OVERVIEW);
        String before = fxGet(() -> text("dayLabel"));
        press("Next Day ▸");
        settle();
        press("Next Week ▸▸");
        settle();
        press("Next Month ▸▸▸");
        settle();
        assertNotEquals(before, fxGet(() -> text("dayLabel")));
        assertTrue(new com.stocksim.service.AlertService().list(userId).stream().anyMatch(PriceAlert::triggered));
        shot("08-overview");

        open(Page.GOALS);
        shot("09-alerts");

        // A scenario from start to result.
        open(Page.LAB);
        press("Start scenario");
        settle();
        press("Next Day ▸");
        settle();
        shot("10-scenario-running");
        press("Run to the end");
        settle();
        assertEquals("COMPLETED", TestSupport.scalar(
                "SELECT status FROM scenario_runs WHERE user_id = ?", userId));
        assertTrue(rows("stockTable") > 0);
        press("Export CSV");
        settle();
        shot("11-scenario-results");

        // What-if never changes the account.
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(1));
        settle();
        Object cash = TestSupport.scalar("SELECT balance FROM users WHERE user_id = ?", userId);
        press("Run experiment");
        assertFalse(fxGet(() -> text("messageLabel")).isEmpty());
        type("percentField", "-15");
        press("Add movement");
        fx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Object> scope = (ComboBox<Object>) find("scopeBox");
            scope.getSelectionModel().select(2);
        });
        type("percentField", "-5");
        press("Add movement");
        press("Preview");
        settle();
        press("Run experiment");
        settle();
        assertEquals(3, rows("table"));
        assertEquals(cash, TestSupport.scalar("SELECT balance FROM users WHERE user_id = ?", userId));
        shot("12-what-if");

        // Backtests with each strategy; invalid input is rejected.
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(2));
        settle();
        type("capitalField", "abc");
        press("Run backtest");
        assertFalse(fxGet(() -> text("errorLabel")).isEmpty());
        type("capitalField", "100000");
        for (int strategy = 2; strategy >= 0; strategy--) {
            int index = strategy == 0 ? 1 : strategy == 1 ? 2 : 0;
            fx(() -> ((ComboBox<?>) find("strategyBox")).getSelectionModel().select(index));
            press("Run backtest");
            settle();
        }
        assertEquals(3, TestSupport.count("backtest_runs", userId));
        select("savedTable", 0);
        press("Open");
        settle();
        press("Export CSV");
        settle();
        shot("13-backtest");

        // Sell from the portfolio.
        open(Page.PORTFOLIO);
        assertEquals(3, rows("table"));
        select("table", 0);
        trade("SELL", 5);
        press("Export CSV");
        settle();
        shot("14-portfolio");

        open(Page.RISK);
        assertTrue(rows("stressTable") > 0);
        shot("15-risk");
        open(Page.PERFORMANCE);
        press("Export CSV");
        settle();
        shot("16-performance");

        open(Page.TRANSACTIONS);
        assertEquals(4, rows("table"));
        fx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<String> type = (ComboBox<String>) find("typeBox");
            type.setValue("SELL");
        });
        assertEquals(1, rows("table"));
        select("table", 0);
        press("Export CSV");
        settle();
        shot("17-transactions");

        open(Page.WATCHLIST);
        press("Add to watchlist");
        settle();
        assertEquals(2, rows("table"));
        shot("18-watchlist");
        select("table", 0);
        press("Create alert");
        settle();
        assertEquals(Page.GOALS, shell.currentPage());
        open(Page.WATCHLIST);
        select("table", 0);
        press("Remove");
        settle();
        assertEquals(1, rows("table"));

        open(Page.SETTINGS);
        press("Save preferences");
        settle();
        press("Save snapshot");
        settle();
        shot("19-settings");

        // Logout, failed login, successful login with the data intact.
        press("Logout");
        assertFalse(UserSession.getInstance().isLoggedIn());
        press("Sign in");
        type("identifierField", username);
        type("passwordField", "wrongpassword");
        press("Sign in");
        await(() -> text("errorLabel").length() > 0 && !text("errorLabel").startsWith("Signing"), "login error");
        shot("20-login-error");
        type("identifierField", username + "@example.com");
        type("passwordField", TestSupport.PASSWORD);
        press("Sign in");
        await(() -> find("sidebar") != null, "shell after login");
        settle();
        open(Page.PORTFOLIO);
        assertEquals(3, rows("table"));

        remainingControls(userId, username);

        // The smallest supported window still lays out.
        fx(() -> {
            stage.setWidth(1100);
            stage.setHeight(700);
        });
        open(Page.OVERVIEW);
        shot("21-overview-small");
        open(Page.MARKET);
        shot("22-market-small");

        assertEquals(List.of(), PROBLEMS);
    }

    /** Every control the main journey did not already press. */
    private static void remainingControls(int userId, String username) throws Exception {
        // Notifications, the user menu and shortcuts between pages.
        fx(() -> ((Button) find("bellButton")).fire());
        settle();
        fx(() -> Window.getWindows().stream().filter(w -> w instanceof ContextMenu).toList()
                .forEach(Window::hide));
        fx(() -> ((MenuButton) find("userMenu")).getItems().get(0).fire());
        settle();
        assertEquals(Page.SETTINGS, Navigator.shell().currentPage());
        open(Page.RISK);
        press("Try a custom stress test");
        settle();
        assertEquals(Page.LAB, Navigator.shell().currentPage());

        // What-if: remove one movement, then reset.
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(1));
        press("Remove selected");
        fx(() -> ((ListView<?>) find("shockList")).getSelectionModel().select(0));
        press("Remove selected");
        press("Reset");
        assertEquals(0, (int) fxGet(() -> ((ListView<?>) find("shockList")).getItems().size()));

        // Cancel a running scenario and delete a saved backtest.
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(0));
        press("Start scenario");
        press("Cancel scenario");
        settle();
        assertEquals(1L, TestSupport.scalar(
                "SELECT COUNT(*) FROM scenario_runs WHERE user_id = ? AND status = 'CANCELLED'", userId));
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(2));
        select("savedTable", 0);
        press("Delete");
        settle();
        assertEquals(2, TestSupport.count("backtest_runs", userId));

        // Market filters and sorting.
        open(Page.MARKET);
        int all = rows("table");
        choose("sectorBox", 1);
        assertTrue(rows("table") < all);
        choose("sectorBox", 0);
        for (int sort = 5; sort >= 0; sort--) {
            choose("sortBox", sort);
        }
        fx(() -> ((CheckBox) find("watchedBox")).setSelected(true));
        assertEquals((int) TestSupport.count("watchlist", userId), rows("table"));
        fx(() -> ((CheckBox) find("watchedBox")).setSelected(false));

        // Stock page actions.
        select("table", 1);
        press("Open full analysis");
        press("☆ Watch");
        settle();
        trade("BUY", 1);
        press("Create alert");
        settle();
        assertEquals(Page.GOALS, Navigator.shell().currentPage());
        open(Page.MARKET);
        press("Open full analysis");
        press("← Market");
        settle();
        assertEquals(Page.MARKET, Navigator.shell().currentPage());

        // Delete alerts; edit and delete the goal.
        open(Page.GOALS);
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(1));
        long alertCount = TestSupport.count("alerts", userId);
        select("activeTable", 0);
        fx(() -> deleteButton("activeTable").fire());
        settle();
        select("triggeredTable", 0);
        fx(() -> deleteButton("triggeredTable").fire());
        settle();
        assertEquals(alertCount - 2, TestSupport.count("alerts", userId));
        fx(() -> ((TabPane) find("tabs")).getSelectionModel().select(0));
        select("goalTable", 0);
        type("goalName", "Renamed goal");
        press("Save changes");
        settle();
        assertEquals("Renamed goal", TestSupport.scalar("SELECT goal_name FROM goals WHERE user_id = ?", userId));
        select("goalTable", 0);
        press("Delete");
        settle();
        assertEquals(0, TestSupport.count("goals", userId));

        // Change the password, then reset the simulation with it.
        String changed = "changed-password-1";
        open(Page.SETTINGS);
        type("currentField", TestSupport.PASSWORD);
        type("newField", changed);
        type("repeatField", changed);
        press("Change password");
        settle();
        new com.stocksim.service.AuthService().login(username, changed);
        type("resetField", changed);
        press("Reset my simulation");
        settle();
        assertEquals(0, TestSupport.count("holdings", userId));
        assertEquals(0, TestSupport.count("transactions", userId));
        open(Page.OVERVIEW);
        shot("23-after-reset");
        open(Page.SETTINGS);
        type("currentField", changed);
        type("newField", TestSupport.PASSWORD);
        type("repeatField", TestSupport.PASSWORD);
        press("Change password");
        settle();
        // Restore three holdings for the checks that follow.
        open(Page.MARKET);
        for (int row : new int[] {0, 3, 6}) {
            select("table", row);
            trade("BUY", 2);
        }
    }

    /** The "Delete selected" button in the same panel as a table. */
    private static Button deleteButton(String tableId) {
        return find(tableId).getParent().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Delete selected".equals(b.getText())).findFirst().orElseThrow();
    }

    private static void choose(String id, int index) throws Exception {
        fx(() -> ((ComboBox<?>) find(id)).getSelectionModel().select(index));
        settle();
    }

    // ---------- helpers ----------

    private static void open(Page page) throws Exception {
        fx(() -> Navigator.shell().open(page));
        settle();
    }

    private static void buy(int quantity) throws Exception {
        settle();
        trade("BUY", quantity);
    }

    /** Presses a Buy or Sell button and completes the order ticket. */
    private static void trade(String side, int quantity) throws Exception {
        press(side);
        await(() -> ticket() != null, "order ticket");
        fx(() -> {
            DialogPane pane = ticket();
            TextField field = (TextField) pane.lookupAll(".text-field").iterator().next();
            field.setText(String.valueOf(quantity));
        });
        if (quantity == 20) {
            fx(() -> save(ticket().getScene(), "05a-order-ticket"));
        }
        fx(() -> ticket().getButtonTypes().stream().filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .forEach(t -> ((Button) ticket().lookupButton(t)).fire()));
        await(() -> ticket() == null, "order ticket to close");
        settle();
    }

    private static DialogPane ticket() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private static Parent root() {
        Parent root = stage.getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }

    private static boolean visible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Node find(String id) {
        return root().lookupAll("#" + id).stream().filter(UiJourneyTest::visible).findFirst().orElse(null);
    }

    private static String text(String id) {
        Node node = find(id);
        return node instanceof Labeled labeled ? labeled.getText() : "";
    }

    private static ToggleButton toggle(String text) {
        return root().lookupAll(".toggle-button").stream().map(n -> (ToggleButton) n)
                .filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static void press(String text) throws Exception {
        settle();
        fx(() -> {
            Button button = root().lookupAll(".button").stream().filter(UiJourneyTest::visible)
                    .filter(n -> n instanceof Button b && text.equals(b.getText())).map(n -> (Button) n)
                    .findFirst().orElseThrow(() -> new AssertionError("No button " + text));
            assertFalse(button.isDisabled(), "Button is disabled: " + text);
            button.fire();
        });
    }

    private static void type(String id, String value) throws Exception {
        fx(() -> ((TextField) find(id)).setText(value));
    }

    private static int rows(String id) throws Exception {
        return fxGet(() -> ((TableView<?>) find(id)).getItems().size());
    }

    private static void select(String id, int row) throws Exception {
        settle();
        fx(() -> ((TableView<?>) find(id)).getSelectionModel().select(row));
        settle();
    }

    /** Waits until background work has been delivered, then fails on any error notice. */
    private static void settle() throws Exception {
        int quiet = 0;
        long end = System.currentTimeMillis() + 60_000;
        while (quiet < 3) {
            quiet = fxGet(() -> Async.pending().get() == 0) ? quiet + 1 : 0;
            assertTrue(System.currentTimeMillis() < end, "Background work did not finish");
            Thread.sleep(25);
        }
        List<String> errors = fxGet(() -> root().lookupAll(".toast-error").stream()
                .flatMap(n -> n.lookupAll(".label").stream()).map(n -> ((Label) n).getText()).toList());
        assertEquals(List.of(), errors);
        assertEquals(List.of(), PROBLEMS);
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (!fxGet(condition::getAsBoolean)) {
            assertTrue(System.currentTimeMillis() < end, "Timed out waiting for " + what);
            Thread.sleep(40);
        }
    }

    private static void fx(Runnable action) throws Exception {
        fxGet(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T fxGet(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void shot(String name) throws Exception {
        settle();
        // Notifications fade on a timer; clear them so they do not hide content.
        fx(() -> {
            Node toasts = find("toasts");
            if (toasts instanceof javafx.scene.layout.VBox box) {
                box.getChildren().clear();
            }
        });
        Thread.sleep(250);
        fx(() -> save(stage.getScene(), name));
        fx(() -> {
            // Whole-page images for screens that scroll, including the tabs inside Simulation Lab.
            for (Node node : root().lookupAll(".scroll-pane")) {
                if (visible(node) && node.getStyleClass().contains("content-scroll") && node instanceof ScrollPane scroll
                        && scroll.getContent() instanceof javafx.scene.layout.Region page
                        && !(page instanceof javafx.scene.layout.VBox box && box.getStyleClass().contains("lab"))
                        && page.getHeight() > scroll.getViewportBounds().getHeight() + 40) {
                    write(page.snapshot(null, null), name + "-full");
                }
            }
        });
    }

    private static void save(Scene scene, String name) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        write(scene.snapshot(null), name);
    }

    /** A minimal PNG writer, so screenshots need no Swing or extra library. */
    private static void write(WritableImage image, String name) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (DeflaterOutputStream zip = new DeflaterOutputStream(data, new Deflater(6))) {
                for (int y = 0; y < h; y++) {
                    zip.write(0);
                    for (int x = 0; x < w; x++) {
                        int argb = pixels.getArgb(x, y);
                        zip.write(argb >> 16);
                        zip.write(argb >> 8);
                        zip.write(argb);
                    }
                }
            }
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(header);
            out.writeInt(w);
            out.writeInt(h);
            out.write(new byte[] {8, 2, 0, 0, 0});
            try (DataOutputStream png = new DataOutputStream(Files.newOutputStream(SHOTS.resolve(name + ".png")))) {
                png.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
                chunk(png, "IHDR", header.toByteArray());
                chunk(png, "IDAT", data.toByteArray());
                chunk(png, "IEND", new byte[0]);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void chunk(DataOutputStream png, String type, byte[] body) throws IOException {
        byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(tag);
        crc.update(body);
        png.writeInt(body.length);
        png.write(tag);
        png.write(body);
        png.writeInt((int) crc.getValue());
    }
}
