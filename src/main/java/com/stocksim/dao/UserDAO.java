package com.stocksim.dao;

import com.stocksim.model.User;
import com.stocksim.model.UserSettings;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** SQL for users and user_settings. */
public class UserDAO extends BaseDAO {

    /** A user together with the stored password hash, used only by authentication. */
    public record Credentials(User user, String passwordHash) {
    }

    public int insert(Connection c, String name, String username, String email, String hash, BigDecimal capital)
            throws SQLException {
        return insert(c, "INSERT INTO users (name, username, email, password, balance, starting_capital) "
                + "VALUES (?,?,?,?,?,?)", name, username, email, hash, capital, capital);
    }

    public Optional<User> findById(Connection c, int userId) throws SQLException {
        return queryOne(c, rs -> map(rs).user(), "SELECT * FROM users WHERE user_id = ?", userId);
    }

    public Optional<Credentials> findByUsernameOrEmail(Connection c, String identifier) throws SQLException {
        return queryOne(c, UserDAO::map,
                "SELECT * FROM users WHERE username = ? OR email = ? ORDER BY username = ? DESC",
                identifier, identifier, identifier);
    }

    public Optional<Credentials> findCredentials(Connection c, int userId) throws SQLException {
        return queryOne(c, UserDAO::map, "SELECT * FROM users WHERE user_id = ?", userId);
    }

    public boolean usernameExists(Connection c, String username) throws SQLException {
        return queryOne(c, rs -> 1, "SELECT 1 FROM users WHERE username = ?", username).isPresent();
    }

    public boolean emailExists(Connection c, String email) throws SQLException {
        return queryOne(c, rs -> 1, "SELECT 1 FROM users WHERE email = ?", email).isPresent();
    }

    /** Reads the wallet and locks the row until the transaction ends. */
    public Optional<BigDecimal> lockBalance(Connection c, int userId) throws SQLException {
        return queryOne(c, rs -> rs.getBigDecimal(1),
                "SELECT balance FROM users WHERE user_id = ? FOR UPDATE", userId);
    }

    public void updateBalance(Connection c, int userId, BigDecimal balance) throws SQLException {
        if (update(c, "UPDATE users SET balance = ? WHERE user_id = ?", balance, userId) != 1) {
            throw new SQLException("Wallet update affected no rows");
        }
    }

    public void updatePassword(Connection c, int userId, String hash) throws SQLException {
        update(c, "UPDATE users SET password = ? WHERE user_id = ?", hash, userId);
    }

    public void delete(Connection c, int userId) throws SQLException {
        update(c, "DELETE FROM users WHERE user_id = ?", userId);
    }

    public UserSettings settings(Connection c, int userId) throws SQLException {
        return queryOne(c, rs -> new UserSettings(rs.getBoolean("confirm_trades"), rs.getString("export_dir"),
                        rs.getInt("seen_alert_day")),
                "SELECT * FROM user_settings WHERE user_id = ?", userId)
                .orElse(new UserSettings(true, null, 0));
    }

    public void saveSettings(Connection c, int userId, UserSettings s) throws SQLException {
        update(c, "REPLACE INTO user_settings (user_id, confirm_trades, export_dir, seen_alert_day) "
                + "VALUES (?,?,?,?)", userId, s.confirmTrades(), s.exportDir(), s.seenAlertDay());
    }

    private static Credentials map(java.sql.ResultSet rs) throws SQLException {
        User user = new User(rs.getInt("user_id"), rs.getString("name"), rs.getString("username"),
                rs.getString("email"), rs.getBigDecimal("balance"), rs.getBigDecimal("starting_capital"),
                rs.getTimestamp("created_at").toLocalDateTime());
        return new Credentials(user, rs.getString("password"));
    }
}
