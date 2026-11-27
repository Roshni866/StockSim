package com.stocksim.controller;

import com.stocksim.model.MarketEvent;
import com.stocksim.model.PriceAlert;
import com.stocksim.model.ScenarioRun;
import com.stocksim.model.SimulationState;
import com.stocksim.model.User;
import com.stocksim.service.AlertService;
import com.stocksim.service.MarketService;
import com.stocksim.service.ScenarioService;
import com.stocksim.service.SimulationService;
import com.stocksim.ui.Async;
import com.stocksim.ui.Dialogs;
import com.stocksim.ui.Ui;
import com.stocksim.util.DataSeeder;
import com.stocksim.util.Formats;
import com.stocksim.util.UserSession;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The persistent window frame: sidebar, top bar, content area and notifications. */
public class ShellController {
    @FXML private VBox sidebar;
    @FXML private Label dateLabel;
    @FXML private Label dayLabel;
    @FXML private Label statusLabel;
    @FXML private Label scenarioLabel;
    @FXML private Label sourceLabel;
    @FXML private Button bellButton;
    @FXML private MenuButton userMenu;
    @FXML private ProgressIndicator busy;
    @FXML private ScrollPane content;
    @FXML private VBox toasts;

    private final SimulationService simulation = new SimulationService();
    private final ScenarioService scenarios = new ScenarioService();
    private final MarketService market = new MarketService();
    private final AlertService alerts = new AlertService();
    private final Map<Page, Node> views = new EnumMap<>(Page.class);
    private final Map<Page, PageController> controllers = new EnumMap<>(Page.class);
    private final Map<Page, Button> navigation = new EnumMap<>(Page.class);
    private Page current;

    /** What the top bar shows. */
    private record Status(SimulationState state, int lastDay, LocalDate date, ScenarioRun run, String source,
                          long unseen) {
    }

    @FXML
    private void initialize() {
        sidebar.getChildren().add(Ui.label("WORKSPACE", "nav-section"));
        for (Page page : Page.values()) {
            if (page == Page.STOCK) {
                continue;
            }
            if (page == Page.GOALS) {
                sidebar.getChildren().add(Ui.label("ACCOUNT", "nav-section"));
            }
            Button button = Ui.button(page.title(), () -> open(page), "nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            navigation.put(page, button);
            sidebar.getChildren().add(button);
        }
        Button logout = Ui.button("Logout", this::logout, "nav-button");
        logout.setMaxWidth(Double.MAX_VALUE);
        logout.setId("logout");
        sidebar.getChildren().add(logout);

        User user = UserSession.getInstance().getCurrentUser();
        userMenu.setText(user.name());
        javafx.scene.control.MenuItem settings = new javafx.scene.control.MenuItem("Settings");
        settings.setOnAction(e -> open(Page.SETTINGS));
        javafx.scene.control.MenuItem out = new javafx.scene.control.MenuItem("Logout");
        out.setOnAction(e -> logout());
        userMenu.getItems().setAll(settings, out);
        bellButton.setOnAction(e -> showNotifications());
        busy.visibleProperty().bind(Async.pending().greaterThan(0));
    }

    public Page currentPage() {
        return current;
    }

    @SuppressWarnings("unchecked")
    public <T extends PageController> T controller(Page page) {
        if (!controllers.containsKey(page)) {
            FXMLLoader loader = Navigator.loader(page.fxml());
            Node view = Navigator.load(loader);
            PageController controller = loader.getController();
            controller.attach(this);
            views.put(page, view);
            controllers.put(page, controller);
        }
        return (T) controllers.get(page);
    }

    /** Shows a workspace in the content area and refreshes it. */
    public void open(Page page) {
        PageController controller = controller(page);
        current = page;
        content.setContent(views.get(page));
        content.setVvalue(0);
        Page highlighted = page == Page.STOCK ? Page.MARKET : page;
        navigation.forEach((p, button) -> {
            button.getStyleClass().remove("active");
            if (p == highlighted) {
                button.getStyleClass().add("active");
            }
        });
        controller.refresh();
        refreshStatus();
    }

    public void openStock(int stockId) {
        StockController stock = controller(Page.STOCK);
        stock.select(stockId);
        open(Page.STOCK);
    }

    /** Loads data in the background and renders it; used by page refreshes. */
    public <T> void load(Callable<T> work, Consumer<T> render) {
        Async.run(work, render, e -> toast("Could not load data", Dialogs.message(e), "toast-error"));
    }

    /** A user action: the page is locked until it completes, then everything refreshes. */
    public <T> void act(Callable<T> work, Consumer<T> done) {
        content.setDisable(true);
        Async.run(work, result -> {
            content.setDisable(false);
            done.accept(result);
            refresh();
        }, e -> {
            content.setDisable(false);
            toast("Not completed", Dialogs.message(e), "toast-error");
            refresh();
        });
    }

    /** An action with no result that reports success with a message. */
    public void act(Runnable work, String success) {
        act(() -> {
            work.run();
            return true;
        }, x -> toast("Done", success, "toast-success"));
    }

    public void export(Callable<Path> work) {
        act(work, path -> toast("Report exported", path.toString(), "toast-success"));
    }

    public void refresh() {
        if (current != null) {
            controllers.get(current).refresh();
            refreshStatus();
        }
    }

    /** Next Day / Week / Month. */
    public void advance(int days) {
        int userId = UserSession.getInstance().requireUserId();
        act(() -> simulation.advance(userId, days), this::announce);
    }

    public void announce(SimulationService.AdvanceResult r) {
        if (r.daysAdvanced() == 0) {
            toast("End of data", "Your simulation is on the last available day.", "toast-warning");
            return;
        }
        List<String> lines = new ArrayList<>();
        r.events().stream().map(MarketEvent::title).limit(3)
                .forEach(t -> lines.add("Simulated event: " + t));
        for (PriceAlert a : r.triggeredAlerts()) {
            lines.add("Alert: " + a.symbol() + " " + a.direction().name().toLowerCase() + " "
                    + Formats.money(a.target()) + " (at " + Formats.money(a.triggeredPrice()) + ")");
        }
        if (r.completedRunId() != null) {
            lines.add("Scenario complete. Results are in Simulation Lab.");
        }
        if (r.reachedEnd()) {
            lines.add("This is the last day with price data.");
        }
        toast("Advanced to day " + r.newDay(), lines.isEmpty() ? "Prices and portfolio updated."
                : String.join("\n", lines), r.triggeredAlerts().isEmpty() ? "toast-info" : "toast-warning");
    }

    public void toast(String title, String message, String style) {
        Label body = Ui.note(message, "toast-body");
        VBox box = new VBox(3, Ui.label(title, "toast-title"), body);
        box.getStyleClass().addAll("toast", style);
        box.setMaxWidth(420);
        box.setOnMouseClicked(e -> toasts.getChildren().remove(box));
        toasts.getChildren().add(box);
        if (toasts.getChildren().size() > 3) {
            toasts.getChildren().remove(0);
        }
        PauseTransition wait = new PauseTransition(Duration.seconds(style.equals("toast-error") ? 9 : 6));
        wait.setOnFinished(e -> toasts.getChildren().remove(box));
        wait.play();
    }

    private void refreshStatus() {
        int userId = UserSession.getInstance().requireUserId();
        load(() -> {
            SimulationState state = simulation.state(userId);
            return new Status(state, market.lastDay(), market.dateOf(state.currentDay()),
                    scenarios.activeRun(userId).orElse(null), market.dataSource(), alerts.unseenCount(userId));
        }, s -> {
            dateLabel.setText(Formats.date(s.date()));
            dayLabel.setText("DAY " + s.state().currentDay() + " / " + s.lastDay());
            boolean ended = s.state().currentDay() >= s.lastDay();
            statusLabel.setText(ended ? "END OF DATA" : s.run() != null ? "SCENARIO" : "READY");
            statusLabel.getStyleClass().setAll("label", "badge",
                    ended ? "badge-red" : s.run() != null ? "badge-amber" : "badge-green");
            boolean running = s.run() != null;
            scenarioLabel.setVisible(running);
            scenarioLabel.setManaged(running);
            if (running) {
                scenarioLabel.setText(s.run().title() + "  " + (s.run().lastDay() - s.run().startDay()) + "/"
                        + (s.run().endDay() - s.run().startDay()) + " (hypothetical)");
            }
            sourceLabel.setText(DataSeeder.SOURCE_SYNTHETIC.equals(s.source())
                    ? "SYNTHETIC SAMPLE DATA" : "IMPORTED HISTORICAL DATA");
            bellButton.setText(s.unseen() > 0 ? "Alerts (" + s.unseen() + ")" : "Alerts");
            bellButton.getStyleClass().remove("has-alerts");
            if (s.unseen() > 0) {
                bellButton.getStyleClass().add("has-alerts");
            }
        });
    }

    private void showNotifications() {
        int userId = UserSession.getInstance().requireUserId();
        load(() -> {
            List<String> lines = new ArrayList<>();
            alerts.list(userId).stream().filter(PriceAlert::triggered).limit(5).forEach(a -> lines.add(
                    "ALERT  day " + a.triggeredDay() + ": " + a.symbol() + " "
                            + a.direction().name().toLowerCase() + " " + Formats.money(a.target())));
            market.recentEvents(userId, 5).forEach(e -> lines.add("EVENT  day " + e.day() + ": " + e.title()));
            return lines;
        }, lines -> {
            ContextMenu menu = new ContextMenu();
            if (lines.isEmpty()) {
                lines.add("No notifications yet.");
            }
            for (String line : lines) {
                CustomMenuItem item = new CustomMenuItem(Ui.label(line));
                item.setOnAction(e -> open(Page.GOALS));
                menu.getItems().add(item);
            }
            menu.show(bellButton, Side.BOTTOM, 0, 4);
            Async.run(() -> {
                alerts.markSeen(userId);
                return true;
            }, x -> refreshStatus(), e -> toast("Notifications", Dialogs.message(e), "toast-error"));
        });
    }

    /** Reusable Next Day / Week / Month buttons. */
    HBox timeControls() {
        HBox box = new HBox(6, Ui.button("Next Day ▸", () -> advance(SimulationService.DAY), "primary"),
                Ui.button("Next Week ▸▸", () -> advance(SimulationService.WEEK)),
                Ui.button("Next Month ▸▸▸", () -> advance(SimulationService.MONTH)));
        box.setId("time-controls");
        return box;
    }

    public void logout() {
        if (Dialogs.confirm("Sign out of StockSim?", "Your portfolio and simulation are saved in the database.")) {
            Navigator.logout();
        }
    }
}
