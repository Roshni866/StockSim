package com.stocksim.service;

import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.exception.AuthenticationException;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.User;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.Database;
import com.stocksim.util.Money;
import com.stocksim.util.PasswordUtil;
import com.stocksim.util.Validator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Registration, login and password changes. Passwords are never logged. */
public class AuthService {
    private static final Logger LOGGER = Logger.getLogger(AuthService.class.getName());
    private static final String BAD_LOGIN = "Invalid username/email or password.";
    private final UserDAO users = new UserDAO();
    private final StockDAO stocks = new StockDAO();
    private final SimulationDAO simulation = new SimulationDAO();
    private final PortfolioService portfolio = new PortfolioService();

    /** Creates the account, wallet, simulation state and first portfolio snapshot together. */
    public User register(String name, String username, String email, String password, String confirmation) {
        String cleanName = name == null ? "" : name.trim();
        String cleanUser = username == null ? "" : username.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return Database.transaction(c -> {
            List<String> errors = validate(c, cleanName, cleanUser, cleanEmail, password, confirmation);
            if (!errors.isEmpty()) {
                throw new ValidationException(String.join("\n", errors));
            }
            BigDecimal capital = Money.of(ConfigLoader.getDecimal("sim.starting.balance", "100000"));
            int id = users.insert(c, cleanName, cleanUser, cleanEmail, PasswordUtil.hash(password), capital);
            initialiseSimulation(c, id);
            LOGGER.info("User registered: " + cleanUser);
            return users.findById(c, id).orElseThrow();
        });
    }

    /** Puts a user on the configured first day with a fresh value history. */
    void initialiseSimulation(Connection c, int userId) throws SQLException {
        int lastDay = stocks.lastDay(c);
        if (lastDay < 2) {
            throw new ValidationException("No price history is loaded, so a simulation cannot start.");
        }
        int startDay = Math.max(1, Math.min(ConfigLoader.getInt("sim.start.day", 261), lastDay / 2));
        simulation.create(c, userId, startDay);
        portfolio.snapshot(c, userId, startDay);
    }

    private List<String> validate(Connection c, String name, String username, String email, String password,
                                  String confirmation) throws SQLException {
        List<String> errors = new ArrayList<>();
        if (name.isEmpty() || name.length() > 100) {
            errors.add("Enter your full name (up to 100 characters).");
        }
        if (!Validator.isValidUsername(username)) {
            errors.add("Username must be 3-50 letters, digits or underscores.");
        } else if (users.usernameExists(c, username)) {
            errors.add("That username is already taken.");
        }
        if (!Validator.isValidEmail(email)) {
            errors.add("Enter a valid email address.");
        } else if (users.emailExists(c, email)) {
            errors.add("An account with that email already exists.");
        }
        if (!Validator.isValidPassword(password)) {
            errors.add("Password needs at least 8 characters (at most 72) including a letter and a digit.");
        } else if (!password.equals(confirmation)) {
            errors.add("Passwords do not match.");
        }
        return errors;
    }

    /** Accepts a username or an email address. */
    public User login(String identifier, String password) throws AuthenticationException {
        if (!Validator.isNotEmpty(identifier) || password == null || password.isEmpty()) {
            throw new AuthenticationException("Enter your username or email and your password.");
        }
        UserDAO.Credentials found = Database.read(c -> users.findByUsernameOrEmail(c, identifier.trim()))
                .orElse(null);
        if (found == null || !PasswordUtil.matches(password, found.passwordHash())) {
            LOGGER.warning("A login attempt failed.");
            throw new AuthenticationException(BAD_LOGIN);
        }
        int userId = found.user().userId();
        if (PasswordUtil.isLegacy(found.passwordHash()) && password.getBytes().length <= 72) {
            Database.transaction(c -> {
                users.updatePassword(c, userId, PasswordUtil.hash(password));
                return null;
            });
            LOGGER.info("Upgraded stored password hash to BCrypt for user " + userId);
        }
        portfolio.backfill(userId);
        LOGGER.info("Login successful for user " + userId);
        return refresh(userId);
    }

    /** Re-reads the account, e.g. after the wallet changed. */
    public User refresh(int userId) {
        return Database.read(c -> users.findById(c, userId))
                .orElseThrow(() -> new IllegalStateException("Account not found."));
    }

    public boolean verifyPassword(int userId, String password) {
        return Database.read(c -> users.findCredentials(c, userId))
                .map(x -> PasswordUtil.matches(password, x.passwordHash())).orElse(false);
    }

    public void changePassword(int userId, String current, String next, String confirmation) {
        if (!verifyPassword(userId, current)) {
            throw new ValidationException("Your current password is incorrect.");
        }
        if (!Validator.isValidPassword(next)) {
            throw new ValidationException("The new password needs at least 8 characters including a letter and a digit.");
        }
        if (!next.equals(confirmation)) {
            throw new ValidationException("The new passwords do not match.");
        }
        Database.transaction(c -> {
            users.updatePassword(c, userId, PasswordUtil.hash(next));
            return null;
        });
    }
}
