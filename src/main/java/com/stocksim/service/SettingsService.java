package com.stocksim.service;

import com.stocksim.dao.AlertDAO;
import com.stocksim.dao.HoldingDAO;
import com.stocksim.dao.ScenarioDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.TransactionDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.exception.DataAccessException;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Portfolio;
import com.stocksim.model.SimulationState;
import com.stocksim.model.UserSettings;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.Database;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.logging.Logger;

/** Preferences, simulation reset and serialised snapshots. */
public class SettingsService {
    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());
    private final UserDAO users = new UserDAO();
    private final HoldingDAO holdings = new HoldingDAO();
    private final TransactionDAO transactions = new TransactionDAO();
    private final ScenarioDAO scenarios = new ScenarioDAO();
    private final SimulationDAO simulation = new SimulationDAO();
    private final AlertDAO alerts = new AlertDAO();
    private final AuthService auth = new AuthService();
    private final PortfolioService portfolios = new PortfolioService();

    /** A read-only record of where the simulation stood, written with Java serialization. */
    public record SavedSnapshot(int userId, LocalDateTime savedAt, SimulationState state, BigDecimal cash,
                                BigDecimal totalValue) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public UserSettings get(int userId) {
        return Database.read(c -> users.settings(c, userId));
    }

    public void save(int userId, boolean confirmTrades, String exportDir) {
        String dir = exportDir == null || exportDir.isBlank() ? null : exportDir.trim();
        if (dir != null) {
            try {
                Files.createDirectories(Path.of(dir));
            } catch (IOException | InvalidPathException e) {
                throw new ValidationException("That export folder cannot be used: " + e.getMessage());
            }
        }
        Database.transaction(c -> {
            users.saveSettings(c, userId, new UserSettings(confirmTrades, dir,
                    users.settings(c, userId).seenAlertDay()));
            return null;
        });
    }

    /**
     * Restarts the simulation with the starting capital. Trades, holdings, prices
     * and scenario runs are erased; goals, watchlist, alerts and backtests stay.
     */
    public void resetSimulation(int userId, String password) {
        if (!auth.verifyPassword(userId, password)) {
            throw new ValidationException("Password is incorrect. Nothing was reset.");
        }
        Database.transaction(c -> {
            simulation.find(c, userId, true);
            holdings.deleteAll(c, userId);
            transactions.deleteAll(c, userId);
            simulation.clear(c, userId);
            scenarios.deleteRuns(c, userId);
            alerts.rearmAll(c, userId);
            users.updateBalance(c, userId, users.findById(c, userId).orElseThrow().startingCapital());
            UserSettings s = users.settings(c, userId);
            users.saveSettings(c, userId, new UserSettings(s.confirmTrades(), s.exportDir(), 0));
            auth.initialiseSimulation(c, userId);
            return null;
        });
        LOGGER.info("Simulation reset for user " + userId);
    }

    public Path snapshotFile(int userId) {
        Path base = Path.of(ConfigLoader.get("snapshot.file", "simulation_state.ser"));
        return base.resolveSibling("user" + userId + "_" + base.getFileName());
    }

    public Path saveSnapshot(int userId) {
        Portfolio p = portfolios.getPortfolio(userId);
        SimulationState state = Database.read(c -> simulation.find(c, userId, false)).orElseThrow();
        Path file = snapshotFile(userId).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) {
                out.writeObject(new SavedSnapshot(userId, LocalDateTime.now(), state, p.cash(), p.totalValue()));
            }
        } catch (IOException e) {
            throw new DataAccessException("Could not save the snapshot: " + e.getMessage(), e);
        }
        return file;
    }

    public Optional<SavedSnapshot> readSnapshot(int userId) {
        Path file = snapshotFile(userId);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            // Only the classes a snapshot is made of may be deserialised.
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "com.stocksim.**;java.base/*;!*"));
            SavedSnapshot snapshot = (SavedSnapshot) in.readObject();
            return snapshot.userId() == userId ? Optional.of(snapshot) : Optional.empty();
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new DataAccessException("The snapshot file could not be read: " + e.getMessage(), e);
        }
    }
}
