package com.stocksim.service;

import com.stocksim.dao.HoldingDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.dao.TransactionDAO;
import com.stocksim.dao.UserDAO;
import com.stocksim.exception.InsufficientBalanceException;
import com.stocksim.exception.InsufficientSharesException;
import com.stocksim.exception.InvalidQuantityException;
import com.stocksim.exception.TradeException;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.SimulationState;
import com.stocksim.model.Stock;
import com.stocksim.model.TradeType;
import com.stocksim.model.Transaction;
import com.stocksim.util.Database;
import com.stocksim.util.Formats;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Buying and selling with virtual money. Each trade is one database transaction:
 * the rows it depends on are locked, validated and updated together, so a trade
 * either happens completely or not at all.
 */
public class TradingService {
    private static final Logger LOGGER = Logger.getLogger(TradingService.class.getName());
    private static final int MAX_QUANTITY = 10_000_000;
    private final UserDAO users = new UserDAO();
    private final StockDAO stocks = new StockDAO();
    private final HoldingDAO holdings = new HoldingDAO();
    private final SimulationDAO simulation = new SimulationDAO();
    private final PortfolioService portfolio = new PortfolioService();
    private final TransactionDAO transactions;

    public TradingService() {
        this(new TransactionDAO());
    }

    /** Lets tests inject a failing ledger to prove that trades roll back. */
    public TradingService(TransactionDAO transactions) {
        this.transactions = transactions;
    }

    public Transaction buy(int userId, int stockId, int quantity) throws TradeException {
        return execute(userId, stockId, quantity, TradeType.BUY);
    }

    public Transaction sell(int userId, int stockId, int quantity) throws TradeException {
        return execute(userId, stockId, quantity, TradeType.SELL);
    }

    private Transaction execute(int userId, int stockId, int quantity, TradeType type) throws TradeException {
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new InvalidQuantityException("Quantity must be a whole number between 1 and 1,00,00,000.");
        }
        return Database.<Transaction, TradeException>transaction(c -> {
            // Locking the simulation row first stops time advancing in the middle of a trade.
            SimulationState state = simulation.find(c, userId, true)
                    .orElseThrow(() -> new ValidationException("Account not found."));
            BigDecimal cash = users.lockBalance(c, userId)
                    .orElseThrow(() -> new ValidationException("Account not found."));
            Stock stock = stocks.findById(c, stockId)
                    .orElseThrow(() -> new ValidationException("That stock does not exist."));
            BigDecimal price = currentPrice(c, userId, stockId, state.currentDay());
            BigDecimal total = Money.times(price, quantity);
            HoldingDAO.Position held = holdings.lock(c, userId, stockId)
                    .orElse(new HoldingDAO.Position(stockId, 0, Money.ZERO));
            BigDecimal realized = null;
            if (type == TradeType.BUY) {
                if (cash.compareTo(total) < 0) {
                    throw new InsufficientBalanceException("This purchase costs " + Formats.money(total)
                            + " but your available cash is " + Formats.money(cash) + ".");
                }
                cash = cash.subtract(total);
                holdings.save(c, userId, stockId, held.quantity() + quantity, held.costBasis().add(total));
            } else {
                if (held.quantity() < quantity) {
                    throw new InsufficientSharesException("You own " + held.quantity() + " share(s) of "
                            + stock.getSymbol() + ", so you cannot sell " + quantity + ".");
                }
                // Average-cost method; selling everything removes the exact remaining cost.
                BigDecimal removed = quantity == held.quantity() ? held.costBasis()
                        : held.costBasis().multiply(BigDecimal.valueOf(quantity))
                                .divide(BigDecimal.valueOf(held.quantity()), 2, RoundingMode.HALF_UP);
                realized = total.subtract(removed);
                cash = cash.add(total);
                holdings.save(c, userId, stockId, held.quantity() - quantity, held.costBasis().subtract(removed));
            }
            users.updateBalance(c, userId, cash);
            int id = transactions.insert(c, userId, stockId, type, quantity, price, total, realized,
                    state.currentDay());
            portfolio.snapshot(c, userId, state.currentDay());
            LOGGER.info(type + " " + quantity + " " + stock.getSymbol() + " @ " + price + " for user " + userId);
            return transactions.findById(c, userId, id).orElseThrow();
        });
    }

    private BigDecimal currentPrice(Connection c, int userId, int stockId, int day) throws SQLException {
        var series = stocks.userSeries(c, userId, stockId, day, day);
        if (series.isEmpty() || series.get(0).price().signum() <= 0) {
            throw new ValidationException("No valid price is available for this stock today.");
        }
        return series.get(0).price();
    }
}
