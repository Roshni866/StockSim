package com.stocksim.service;

import com.stocksim.analytics.MarketIndex;
import com.stocksim.backtesting.BacktestConfig;
import com.stocksim.backtesting.BacktestEngine;
import com.stocksim.backtesting.BacktestResult;
import com.stocksim.dao.BacktestDAO;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.StockPrice;
import com.stocksim.util.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Runs and stores strategy backtests on reference history up to the user's
 * current day. Wallet, holdings and the trade ledger are never written.
 */
public class BacktestService {
    private final StockDAO stocks = new StockDAO();
    private final SimulationDAO simulation = new SimulationDAO();
    private final BacktestDAO backtests = new BacktestDAO();
    private final BacktestEngine engine = new BacktestEngine();

    public record Report(int runId, String symbol, BacktestConfig config, BacktestResult result) {
    }

    /** Runs a backtest and saves it. */
    public Report run(int userId, BacktestConfig config) {
        return Database.transaction(c -> {
            int today = simulation.find(c, userId, false).orElseThrow().currentDay();
            if (config.toDay() > today || config.fromDay() < 1) {
                throw new ValidationException("Choose a period between day 1 and your current day " + today
                        + ". Backtests cannot use prices from your future.");
            }
            BacktestResult result = evaluate(c, config);
            int runId = backtests.save(c, userId, config, result);
            return new Report(runId, symbol(c, config.stockId()), config, result);
        });
    }

    public List<BacktestDAO.SavedRun> history(int userId) {
        return Database.read(c -> backtests.runs(c, userId));
    }

    /** Reopens a saved run; replaying the same history gives the same result. */
    public Report open(int userId, int runId) {
        return Database.read(c -> {
            BacktestDAO.SavedRun saved = backtests.find(c, userId, runId)
                    .orElseThrow(() -> new ValidationException("Backtest not found."));
            return new Report(runId, saved.symbol(), saved.config(), evaluate(c, saved.config()));
        });
    }

    public void delete(int userId, int runId) {
        Database.transaction(c -> {
            if (!backtests.delete(c, userId, runId)) {
                throw new ValidationException("Backtest not found.");
            }
            return null;
        });
    }

    private BacktestResult evaluate(Connection c, BacktestConfig config) throws SQLException {
        Map<Integer, List<StockPrice>> all = stocks.allReferenceSeries(c, 1, config.toDay());
        List<StockPrice> prices = all.get(config.stockId());
        if (prices == null) {
            throw new ValidationException("Choose a stock.");
        }
        if (config.fromDay() >= config.toDay()) {
            throw new ValidationException("The period must start before it ends.");
        }
        Map<Integer, List<StockPrice>> period =
                stocks.allReferenceSeries(c, config.fromDay(), config.toDay());
        return engine.run(config, prices, MarketIndex.of(period.values()));
    }

    private String symbol(Connection c, int stockId) throws SQLException {
        return stocks.findById(c, stockId).orElseThrow().getSymbol();
    }
}
