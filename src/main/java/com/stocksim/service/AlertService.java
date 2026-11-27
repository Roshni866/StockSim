package com.stocksim.service;

import com.stocksim.dao.AlertDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.PriceAlert;
import com.stocksim.model.UserSettings;
import com.stocksim.util.Database;
import com.stocksim.util.Validator;

import java.math.BigDecimal;
import java.util.List;

/** Creating and listing price alerts. Evaluation happens in SimulationService. */
public class AlertService {
    private final AlertDAO alerts = new AlertDAO();
    private final StockDAO stocks = new StockDAO();
    private final UserDAO users = new UserDAO();

    public void create(int userId, int stockId, PriceAlert.Direction direction, BigDecimal target) {
        BigDecimal price = Validator.requireAmount(target, "Target price");
        if (direction == null) {
            throw new ValidationException("Choose above or below.");
        }
        Database.transaction(c -> {
            stocks.findById(c, stockId).orElseThrow(() -> new ValidationException("Choose a stock."));
            boolean duplicate = alerts.findByUser(c, userId).stream().anyMatch(a -> !a.triggered()
                    && a.stockId() == stockId && a.direction() == direction && a.target().compareTo(price) == 0);
            if (duplicate) {
                throw new ValidationException("You already have that alert.");
            }
            alerts.insert(c, userId, stockId, direction, price);
            return null;
        });
    }

    /** Newest first. */
    public List<PriceAlert> list(int userId) {
        return Database.read(c -> alerts.findByUser(c, userId));
    }

    public void delete(int userId, int alertId) {
        Database.transaction(c -> {
            if (!alerts.delete(c, userId, alertId)) {
                throw new ValidationException("Alert not found.");
            }
            return null;
        });
    }

    /** Triggered alerts the user has not yet looked at. */
    public long unseenCount(int userId) {
        return Database.read(c -> {
            int seen = users.settings(c, userId).seenAlertDay();
            return alerts.findByUser(c, userId).stream()
                    .filter(a -> a.triggered() && a.triggeredDay() != null && a.triggeredDay() > seen).count();
        });
    }

    public void markSeen(int userId) {
        Database.transaction(c -> {
            int newest = alerts.findByUser(c, userId).stream().filter(a -> a.triggeredDay() != null)
                    .mapToInt(PriceAlert::triggeredDay).max().orElse(0);
            UserSettings s = users.settings(c, userId);
            users.saveSettings(c, userId, new UserSettings(s.confirmTrades(), s.exportDir(), newest));
            return null;
        });
    }
}
