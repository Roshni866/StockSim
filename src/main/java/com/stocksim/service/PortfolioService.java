package com.stocksim.service;

import com.stocksim.dao.HoldingDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.TransactionDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.model.Holding;
import com.stocksim.model.Portfolio;
import com.stocksim.model.PortfolioSnapshot;
import com.stocksim.model.SimulationState;
import com.stocksim.model.Stock;
import com.stocksim.model.StockPrice;
import com.stocksim.model.TradeType;
import com.stocksim.model.Transaction;
import com.stocksim.model.User;
import com.stocksim.util.Database;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Values portfolios. Every screen gets its numbers from here, so they always agree. */
public class PortfolioService {
    private final UserDAO users = new UserDAO();
    private final StockDAO stocks = new StockDAO();
    private final HoldingDAO holdings = new HoldingDAO();
    private final TransactionDAO transactions = new TransactionDAO();
    private final SimulationDAO simulation = new SimulationDAO();

    public Portfolio getPortfolio(int userId) {
        return Database.read(c -> load(c, userId));
    }

    public List<PortfolioSnapshot> history(int userId) {
        return Database.read(c -> simulation.snapshots(c, userId));
    }

    public List<Transaction> transactions(int userId) {
        return Database.read(c -> transactions.findByUser(c, userId));
    }

    /** Values the portfolio at the user's current simulation day, on the caller's connection. */
    Portfolio load(Connection c, int userId) throws SQLException {
        User user = users.findById(c, userId).orElseThrow(() -> new IllegalStateException("Account not found."));
        SimulationState state = simulation.find(c, userId, false)
                .orElseThrow(() -> new IllegalStateException("Simulation state not found."));
        Map<Integer, Stock> byId = stocks.findAll(c).stream()
                .collect(Collectors.toMap(Stock::getId, Function.identity()));
        List<Holding> held = holdings.holdings(c, userId, byId, prices(c, userId, state.currentDay())).stream()
                .sorted(Comparator.comparing(Holding::marketValue).reversed()).toList();
        BigDecimal invested = Money.ZERO;
        BigDecimal value = Money.ZERO;
        for (Holding h : held) {
            invested = invested.add(h.getCostBasis());
            value = value.add(h.marketValue());
        }
        BigDecimal realized = transactions.findByUser(c, userId).stream()
                .map(Transaction::realizedProfit).filter(p -> p != null).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal total = user.balance().add(value);
        BigDecimal profit = total.subtract(user.startingCapital());
        return new Portfolio(user.balance(), user.startingCapital(), invested, value, total,
                value.subtract(invested), realized, profit, Money.percent(profit, user.startingCapital()), held);
    }

    /** Each stock's price on one day of this user's simulation. */
    Map<Integer, BigDecimal> prices(Connection c, int userId, int day) throws SQLException {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        stocks.allUserSeries(c, userId, day, day)
                .forEach((id, series) -> result.put(id, series.get(0).price()));
        return result;
    }

    /** Records today's portfolio value for the equity curve. */
    void snapshot(Connection c, int userId, int day) throws SQLException {
        Portfolio p = load(c, userId);
        simulation.saveSnapshot(c, userId, day, p.cash(), p.holdingsValue());
    }

    /**
     * Upgrades an account created by the earlier version: fills in realised
     * profit on old sales and rebuilds the missing value history from the ledger.
     * Does nothing for accounts that are already complete.
     */
    public void backfill(int userId) {
        Database.transaction(c -> {
            SimulationState state = simulation.find(c, userId, true).orElse(null);
            if (state == null) {
                User user = users.findById(c, userId).orElseThrow();
                simulation.create(c, user.userId(), 1);
                state = simulation.find(c, userId, true).orElseThrow();
            }
            List<Transaction> ledger = transactions.findByUser(c, userId);
            Map<Integer, BigDecimal[]> positions = new HashMap<>();
            for (Transaction t : ledger) {
                BigDecimal[] p = positions.computeIfAbsent(t.stockId(),
                        k -> new BigDecimal[] {BigDecimal.ZERO, Money.ZERO});
                BigDecimal quantity = BigDecimal.valueOf(t.quantity());
                if (t.type() == TradeType.BUY) {
                    p[0] = p[0].add(quantity);
                    p[1] = p[1].add(t.total());
                } else if (p[0].signum() > 0) {
                    BigDecimal removed = p[1].multiply(quantity).divide(p[0], 2, RoundingMode.HALF_UP);
                    if (t.realizedProfit() == null) {
                        transactions.updateRealized(c, t.id(), t.total().subtract(removed));
                    }
                    p[0] = p[0].subtract(quantity);
                    p[1] = p[1].subtract(removed);
                }
            }
            if (simulation.snapshots(c, userId).isEmpty()) {
                rebuildSnapshots(c, userId, state, ledger);
            }
            return null;
        });
    }

    private void rebuildSnapshots(Connection c, int userId, SimulationState state, List<Transaction> ledger)
            throws SQLException {
        BigDecimal cash = users.findById(c, userId).orElseThrow().startingCapital();
        Map<Integer, List<StockPrice>> series =
                stocks.allUserSeries(c, userId, state.startDay(), state.currentDay());
        Map<Integer, Integer> shares = new HashMap<>();
        int next = 0;
        for (int day = state.startDay(); day <= state.currentDay(); day++) {
            while (next < ledger.size() && ledger.get(next).simulationDay() <= day) {
                Transaction t = ledger.get(next++);
                int sign = t.type() == TradeType.BUY ? 1 : -1;
                shares.merge(t.stockId(), sign * t.quantity(), Integer::sum);
                cash = sign > 0 ? cash.subtract(t.total()) : cash.add(t.total());
            }
            BigDecimal value = Money.ZERO;
            for (Map.Entry<Integer, Integer> e : shares.entrySet()) {
                BigDecimal price = series.get(e.getKey()).get(day - state.startDay()).price();
                value = value.add(Money.times(price, e.getValue()));
            }
            simulation.saveSnapshot(c, userId, day, cash, value);
        }
        // Today's row always reflects the real wallet.
        snapshot(c, userId, state.currentDay());
    }
}
