package com.stocksim.service;

import com.stocksim.analytics.Indicators;
import com.stocksim.analytics.MarketIndex;
import com.stocksim.dao.SimulationDAO;
import com.stocksim.dao.StockDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.MarketEvent;
import com.stocksim.model.Quote;
import com.stocksim.model.SimulationState;
import com.stocksim.model.Stock;
import com.stocksim.model.StockPrice;
import com.stocksim.util.Database;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Market data as one user sees it on their current simulation day. */
public class MarketService {
    private final StockDAO stocks = new StockDAO();
    private final SimulationDAO simulation = new SimulationDAO();

    /** Equal-weighted index level on a day. */
    public record IndexPoint(int day, LocalDate date, double level) {
    }

    public List<Stock> stocks() {
        return Database.read(stocks::findAll);
    }

    public int lastDay() {
        return Database.read(stocks::lastDay);
    }

    /** IMPORTED_CSV or SYNTHETIC. */
    public String dataSource() {
        return Database.read(stocks::dataSource);
    }

    public LocalDate dateOf(int day) {
        return Database.read(c -> stocks.dates(c, day, day).get(day));
    }

    public Map<Integer, LocalDate> dates(int fromDay, int toDay) {
        return Database.read(c -> stocks.dates(c, fromDay, toDay));
    }

    /** Current quotes for every stock, in symbol order. */
    public List<Quote> quotes(int userId) {
        return Database.read(c -> {
            int day = day(c, userId);
            Map<Integer, List<StockPrice>> series = stocks.allUserSeries(c, userId,
                    Math.max(1, day - Indicators.TRADING_DAYS_PER_YEAR + 1), day);
            List<Quote> result = new ArrayList<>();
            for (Stock stock : stocks.findAll(c)) {
                List<StockPrice> s = series.get(stock.getId());
                if (s == null) {
                    continue;
                }
                BigDecimal now = s.get(s.size() - 1).price();
                BigDecimal before = s.size() > 1 ? s.get(s.size() - 2).price() : now;
                result.add(new Quote(stock, now, now.subtract(before), Money.change(before, now),
                        s.stream().map(StockPrice::price).max(Comparator.naturalOrder()).orElse(now),
                        s.stream().map(StockPrice::price).min(Comparator.naturalOrder()).orElse(now)));
            }
            return result;
        });
    }

    /** Up to {@code points} most recent prices ending today, oldest first. */
    public List<StockPrice> series(int userId, int stockId, int points) {
        return Database.read(c -> {
            int day = day(c, userId);
            return stocks.userSeries(c, userId, stockId, Math.max(1, day - points + 1), day);
        });
    }

    /** The index over the most recent days, based at 1000 on the first of them. */
    public List<IndexPoint> index(int userId, int points) {
        return Database.read(c -> {
            int day = day(c, userId);
            Map<Integer, List<StockPrice>> series =
                    stocks.allUserSeries(c, userId, Math.max(1, day - points + 1), day);
            double[] levels = MarketIndex.of(series.values());
            List<StockPrice> any = series.values().iterator().next();
            List<IndexPoint> result = new ArrayList<>();
            for (int i = 0; i < levels.length; i++) {
                result.add(new IndexPoint(any.get(i).day(), any.get(i).date(), levels[i]));
            }
            return result;
        });
    }

    /** Average daily percentage change of each sector's stocks. */
    public Map<String, BigDecimal> sectorPerformance(List<Quote> quotes) {
        Map<String, List<BigDecimal>> bySector = new TreeMap<>();
        quotes.forEach(q -> bySector.computeIfAbsent(q.stock().getSector(), k -> new ArrayList<>())
                .add(q.changePercent()));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        bySector.forEach((sector, changes) -> result.put(sector,
                changes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(changes.size()), 2, RoundingMode.HALF_UP)));
        return result;
    }

    public List<MarketEvent> recentEvents(int userId, int limit) {
        return Database.read(c -> simulation.recentEvents(c, userId, limit));
    }

    private int day(Connection c, int userId) throws SQLException {
        return simulation.find(c, userId, false).map(SimulationState::currentDay)
                .orElseThrow(() -> new ValidationException("Simulation state not found."));
    }
}
