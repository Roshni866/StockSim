package com.stocksim.dao;

import com.stocksim.backtesting.BacktestConfig;
import com.stocksim.backtesting.BacktestResult;
import com.stocksim.backtesting.BacktestTrade;
import com.stocksim.backtesting.StrategyType;
import com.stocksim.model.TradeType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for backtests. Kept entirely apart from the real transaction ledger. */
public class BacktestDAO extends BaseDAO {

    /** A saved backtest as listed in history. */
    public record SavedRun(int runId, String symbol, BacktestConfig config, BigDecimal finalCapital,
                           BigDecimal totalReturn, LocalDateTime createdAt) {
    }

    private static final String SELECT = "SELECT r.*, s.symbol, x.final_capital, x.total_return_pct "
            + "FROM backtest_runs r JOIN stocks s ON s.stock_id = r.stock_id "
            + "JOIN backtest_results x ON x.run_id = r.run_id WHERE r.user_id = ? ";

    public int save(Connection c, int userId, BacktestConfig k, BacktestResult r) throws SQLException {
        int runId = insert(c, "INSERT INTO backtest_runs (user_id, stock_id, strategy, short_period, long_period, "
                + "lookback, entry_pct, exit_pct, initial_capital, fee_pct, from_day, to_day) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", userId, k.stockId(), k.strategy().name(), k.shortPeriod(),
                k.longPeriod(), k.lookback(), k.entryPercent(), k.exitPercent(), k.initialCapital(), k.feePercent(),
                k.fromDay(), k.toDay());
        update(c, "INSERT INTO backtest_results (run_id, final_capital, total_return_pct, annualized_pct, "
                + "total_trades, winning_trades, losing_trades, win_rate_pct, max_drawdown_pct, avg_trade_return_pct, "
                + "benchmark_return_pct) VALUES (?,?,?,?,?,?,?,?,?,?,?)", runId, r.finalCapital(), r.totalReturn(),
                r.annualizedReturn(), r.trades().size(), r.winningTrades(), r.losingTrades(), r.winRate(),
                r.maxDrawdown(), r.averageTradeReturn(), r.benchmarkReturn());
        batch(c, "INSERT INTO backtest_trades (run_id, simulation_day, trade_date, side, quantity, price, fee, "
                + "profit, note) VALUES (?,?,?,?,?,?,?,?,?)", r.trades().stream().map(t -> new Object[] {runId, t.day(),
                Date.valueOf(t.date()), t.side().name(), t.quantity(), t.price(), t.fee(), t.profit(), t.note()})
                .toList());
        return runId;
    }

    public List<SavedRun> runs(Connection c, int userId) throws SQLException {
        return query(c, BacktestDAO::map, SELECT + "ORDER BY r.run_id DESC", userId);
    }

    public Optional<SavedRun> find(Connection c, int userId, int runId) throws SQLException {
        return queryOne(c, BacktestDAO::map, SELECT + "AND r.run_id = ?", userId, runId);
    }

    public List<BacktestTrade> trades(Connection c, int runId) throws SQLException {
        return query(c, rs -> new BacktestTrade(rs.getInt("simulation_day"), rs.getDate("trade_date").toLocalDate(),
                        TradeType.valueOf(rs.getString("side")), rs.getInt("quantity"), rs.getBigDecimal("price"),
                        rs.getBigDecimal("fee"), rs.getBigDecimal("profit"), rs.getString("note")),
                "SELECT * FROM backtest_trades WHERE run_id = ? ORDER BY trade_id", runId);
    }

    public boolean delete(Connection c, int userId, int runId) throws SQLException {
        return update(c, "DELETE FROM backtest_runs WHERE run_id = ? AND user_id = ?", runId, userId) == 1;
    }

    private static SavedRun map(java.sql.ResultSet rs) throws SQLException {
        BacktestConfig config = new BacktestConfig(rs.getInt("stock_id"), StrategyType.valueOf(rs.getString("strategy")),
                rs.getInt("from_day"), rs.getInt("to_day"), rs.getBigDecimal("initial_capital"),
                rs.getBigDecimal("fee_pct"), rs.getInt("short_period"), rs.getInt("long_period"),
                rs.getInt("lookback"), rs.getBigDecimal("entry_pct"), rs.getBigDecimal("exit_pct"));
        return new SavedRun(rs.getInt("run_id"), rs.getString("symbol"), config, rs.getBigDecimal("final_capital"),
                rs.getBigDecimal("total_return_pct"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
