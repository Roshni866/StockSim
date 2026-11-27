package com.stocksim;

import com.stocksim.dao.TransactionDAO;
import com.stocksim.exception.*;
import com.stocksim.model.*;
import com.stocksim.service.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;

import static com.stocksim.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountAndTradingTest {
    private final AuthService auth = new AuthService();
    private final TradingService trading = new TradingService();
    private final PortfolioService portfolios = new PortfolioService();

    @BeforeAll
    static void setUp() {
        database();
    }

    @Test
    void registrationCreatesWalletStateAndSnapshot() {
        User u = newUser();
        assertEquals(bd("100000.00"), u.balance());
        assertEquals(261, new SimulationService().state(u.userId()).currentDay());
        assertEquals(1, portfolios.history(u.userId()).size());
        assertTrue(portfolios.getPortfolio(u.userId()).holdings().isEmpty());
        String stored = (String) scalar("SELECT password FROM users WHERE user_id = ?", u.userId());
        assertTrue(stored.startsWith("$2"));
        assertFalse(stored.contains(PASSWORD));
    }

    @Test
    void duplicateUsernameAndEmailAreRejected() {
        User u = newUser();
        ValidationException name = assertThrows(ValidationException.class, () -> auth.register("X",
                u.username(), "other" + u.userId() + "@example.com", PASSWORD, PASSWORD));
        assertTrue(name.getMessage().contains("username is already taken"));
        ValidationException mail = assertThrows(ValidationException.class, () -> auth.register("X",
                "other_" + u.userId(), u.email().toUpperCase(), PASSWORD, PASSWORD));
        assertTrue(mail.getMessage().contains("email already exists"));
        assertThrows(ValidationException.class, () -> auth.register("", "a b", "bad", "short", "x"));
    }

    @Test
    void loginByUsernameOrEmailAndRejectWrongPassword() throws Exception {
        User u = newUser();
        assertEquals(u.userId(), auth.login(u.username(), PASSWORD).userId());
        assertEquals(u.userId(), auth.login(u.email(), PASSWORD).userId());
        assertThrows(AuthenticationException.class, () -> auth.login(u.username(), "password999"));
        assertThrows(AuthenticationException.class, () -> auth.login("nobody_here", PASSWORD));
        assertThrows(AuthenticationException.class, () -> auth.login("' OR 1=1 --", PASSWORD));
    }

    @Test
    void passwordCanBeChanged() throws Exception {
        User u = newUser();
        assertThrows(ValidationException.class, () -> auth.changePassword(u.userId(), "wrong", "newpass123", "newpass123"));
        auth.changePassword(u.userId(), PASSWORD, "newpass123", "newpass123");
        assertNotNull(auth.login(u.username(), "newpass123"));
    }

    @Test
    void buyDeductsCashCreatesHoldingAndRecordsTrade() throws Exception {
        User u = newUser();
        int tcs = stock("TCS").getId();
        Transaction t = trading.buy(u.userId(), tcs, 10);
        assertEquals(t.price().multiply(BigDecimal.TEN).setScale(2), t.total());
        Portfolio p = portfolios.getPortfolio(u.userId());
        assertEquals(bd("100000.00").subtract(t.total()), p.cash());
        assertEquals(10, p.holdings().get(0).getQuantity());
        assertEquals(t.total(), p.invested());
        assertEquals(bd("100000.00"), p.totalValue());
        assertEquals(1, portfolios.transactions(u.userId()).size());
    }

    @Test
    void invalidTradesChangeNothing() {
        User u = newUser();
        int tcs = stock("TCS").getId();
        assertThrows(InsufficientBalanceException.class, () -> trading.buy(u.userId(), tcs, 1_000_000));
        assertThrows(InvalidQuantityException.class, () -> trading.buy(u.userId(), tcs, 0));
        assertThrows(InvalidQuantityException.class, () -> trading.sell(u.userId(), tcs, -5));
        assertThrows(InsufficientSharesException.class, () -> trading.sell(u.userId(), tcs, 1));
        assertThrows(ValidationException.class, () -> trading.buy(u.userId(), 9999, 1));
        assertEquals(bd("100000.00"), auth.refresh(u.userId()).balance());
        assertEquals(0, count("transactions", u.userId()));
    }

    @Test
    void averagePriceAndPartialSaleUseExactCostBasis() throws Exception {
        User u = newUser();
        int infy = stock("INFY").getId();
        Transaction first = trading.buy(u.userId(), infy, 3);
        new SimulationService().advance(u.userId(), 21);
        Transaction second = trading.buy(u.userId(), infy, 4);
        Holding h = portfolios.getPortfolio(u.userId()).holdings().get(0);
        BigDecimal cost = first.total().add(second.total());
        assertEquals(cost, h.getCostBasis());
        assertEquals(cost.divide(bd("7"), 2, RoundingMode.HALF_UP), h.averagePrice());

        new SimulationService().advance(u.userId(), 5);
        Transaction sale = trading.sell(u.userId(), infy, 2);
        BigDecimal removed = cost.multiply(bd("2")).divide(bd("7"), 2, RoundingMode.HALF_UP);
        assertEquals(sale.total().subtract(removed), sale.realizedProfit());
        Portfolio p = portfolios.getPortfolio(u.userId());
        assertEquals(5, p.holdings().get(0).getQuantity());
        assertEquals(cost.subtract(removed), p.invested());
        assertEquals(p.totalProfit(), p.realized().add(p.unrealized()), "ledger identity");

        trading.sell(u.userId(), infy, 5);
        p = portfolios.getPortfolio(u.userId());
        assertTrue(p.holdings().isEmpty(), "holding removed at zero");
        assertEquals(p.totalProfit(), p.realized());
        assertEquals(p.cash(), p.totalValue());
    }

    @Test
    void failureInsideATradeRollsEverythingBack() throws Exception {
        User u = newUser();
        int tcs = stock("TCS").getId();
        trading.buy(u.userId(), tcs, 5);
        BigDecimal cash = auth.refresh(u.userId()).balance();
        TradingService broken = new TradingService(new TransactionDAO() {
            @Override
            public int insert(Connection c, int userId, int stockId, TradeType type, int quantity, BigDecimal price,
                              BigDecimal total, BigDecimal realized, int day) throws SQLException {
                throw new SQLException("simulated failure after the wallet and holding were updated");
            }
        });
        assertThrows(DataAccessException.class, () -> broken.buy(u.userId(), tcs, 2));
        assertThrows(DataAccessException.class, () -> broken.sell(u.userId(), tcs, 2));
        assertEquals(cash, auth.refresh(u.userId()).balance());
        assertEquals(5, portfolios.getPortfolio(u.userId()).holdings().get(0).getQuantity());
        assertEquals(1, count("transactions", u.userId()));
    }

    @Test
    void simultaneousPurchasesCannotOverspend() throws Exception {
        User u = newUser();
        int tcs = stock("TCS").getId();
        BigDecimal price = new MarketService().series(u.userId(), tcs, 1).get(0).price();
        int mostShares = u.balance().divide(price, 0, RoundingMode.DOWN).intValue();
        int each = mostShares / 2 + 1;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> buy = () -> {
            try {
                trading.buy(u.userId(), tcs, each);
                return true;
            } catch (InsufficientBalanceException e) {
                return false;
            }
        };
        var results = pool.invokeAll(java.util.List.of(buy, buy));
        pool.shutdown();
        assertNotEquals(results.get(0).get(), results.get(1).get(), "exactly one succeeds");
        assertTrue(auth.refresh(u.userId()).balance().signum() >= 0);
    }

    @Test
    void usersOnlySeeAndChangeTheirOwnData() throws Exception {
        User a = newUser();
        User b = newUser();
        trading.buy(a.userId(), stock("ITC").getId(), 3);
        assertTrue(portfolios.transactions(b.userId()).isEmpty());
        GoalService goals = new GoalService();
        goals.create(a.userId(), "A goal", bd("200000"));
        int goal = goals.list(a.userId()).get(0).id();
        assertThrows(ValidationException.class, () -> goals.delete(b.userId(), goal));
        assertThrows(ValidationException.class, () -> goals.update(b.userId(), goal, "x", bd("1")));
        AlertService alerts = new AlertService();
        alerts.create(a.userId(), stock("ITC").getId(), PriceAlert.Direction.ABOVE, bd("1"));
        assertThrows(ValidationException.class,
                () -> alerts.delete(b.userId(), alerts.list(a.userId()).get(0).id()));
        int run = new ScenarioService().start(a.userId(), 1).runId();
        assertThrows(ValidationException.class, () -> new ScenarioService().report(b.userId(), run));
        assertThrows(ValidationException.class, () -> new ReportService().exportScenario(b.userId(), run));
    }
}
