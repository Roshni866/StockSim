package com.stocksim;

import com.stocksim.model.Portfolio;
import com.stocksim.model.User;
import com.stocksim.service.AuthService;
import com.stocksim.service.PortfolioService;
import com.stocksim.service.SimulationService;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.DataSeeder;
import com.stocksim.util.SchemaMigrator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;

import static com.stocksim.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** A database created by the previous version must upgrade without losing anything. */
class MigrationTest {

    @Test
    void upgradingAnOldDatabaseKeepsAccountsTradesAndPrices() throws Exception {
        database();
        String testUrl = System.getProperty("db.url");
        String legacyUrl = testUrl.replace("/stocksim_test", "/stocksim_test_legacy");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("oldpassword1".getBytes(StandardCharsets.UTF_8)));
        String schema = new String(getClass().getResourceAsStream("/legacy-database.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("stocksim", "stocksim_test_legacy");
        try (Connection c = DriverManager.getConnection(
                legacyUrl.replace("/stocksim_test_legacy", "/") + "&allowMultiQueries=true",
                ConfigLoader.get("db.user", "root"), ConfigLoader.get("db.password", ""));
             Statement st = c.createStatement()) {
            st.execute(schema.replaceAll("(?m)^--.*$", ""));
            while (st.getMoreResults() || st.getUpdateCount() != -1) {
                // drain every statement result
            }
            st.execute("USE stocksim_test_legacy");
            for (int day = 1; day <= 40; day++) {
                st.execute("INSERT INTO stock_history (stock_id, price, recorded_at, simulation_day) "
                        + "SELECT stock_id, 100 + " + day + ", DATE_ADD('2008-01-01', INTERVAL " + day + " DAY), "
                        + day + " FROM stocks");
            }
            st.execute("INSERT INTO users (name, username, email, password, balance, sim_day) VALUES "
                    + "('Old User', 'olduser', 'old@example.com', '" + hash + "', 99490.00, 20)");
            st.execute("INSERT INTO transactions (user_id, stock_id, transaction_type, quantity, price, "
                    + "total_amount, simulation_day) VALUES (1,1,'BUY',10,105,1050,5), (1,1,'SELL',5,108,540,8)");
            st.execute("INSERT INTO holdings (user_id, stock_id, quantity, average_buy_price) VALUES (1,1,5,105)");
        }
        System.setProperty("db.url", legacyUrl);
        try {
            SchemaMigrator.migrate();
            SchemaMigrator.migrate();
            DataSeeder.seedIfEmpty();
            assertEquals(400L, scalar("SELECT COUNT(*) FROM stock_history"));
            assertEquals(DataSeeder.SOURCE_CSV, scalar("SELECT meta_value FROM app_meta"));
            User user = new AuthService().login("olduser", "oldpassword1");
            assertEquals(bd("99490.00"), user.balance());
            assertTrue(((String) scalar("SELECT password FROM users")).startsWith("$2"));
            assertEquals(20, new SimulationService().state(user.userId()).currentDay());
            PortfolioService portfolios = new PortfolioService();
            Portfolio p = portfolios.getPortfolio(user.userId());
            assertEquals(bd("525.00"), p.invested());
            assertEquals(bd("15.00"), p.realized());
            assertEquals(p.totalProfit(), p.realized().add(p.unrealized()));
            var history = portfolios.history(user.userId());
            assertEquals(20, history.size());
            assertEquals(p.totalValue(), history.get(19).totalValue());
            assertEquals(25, new SimulationService().advance(user.userId(), 5).newDay());
        } finally {
            try (Connection c = DriverManager.getConnection(legacyUrl, ConfigLoader.get("db.user", "root"),
                    ConfigLoader.get("db.password", "")); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE stocksim_test_legacy");
            }
            System.setProperty("db.url", testUrl);
        }
    }
}
