package com.stocksim.service;

import com.stocksim.dao.StockDAO;
import com.stocksim.dao.WatchlistDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Quote;
import com.stocksim.util.Database;

import java.util.List;
import java.util.Set;

/** Each user's persisted list of followed stocks. */
public class WatchlistService {
    private final WatchlistDAO watchlist = new WatchlistDAO();
    private final StockDAO stocks = new StockDAO();
    private final MarketService market = new MarketService();

    public Set<Integer> stockIds(int userId) {
        return Database.read(c -> watchlist.stockIds(c, userId));
    }

    public List<Quote> quotes(int userId) {
        Set<Integer> ids = stockIds(userId);
        return market.quotes(userId).stream().filter(q -> ids.contains(q.stock().getId())).toList();
    }

    public void add(int userId, int stockId) {
        Database.transaction(c -> {
            stocks.findById(c, stockId).orElseThrow(() -> new ValidationException("Choose a stock."));
            watchlist.add(c, userId, stockId);
            return null;
        });
    }

    public void remove(int userId, int stockId) {
        Database.transaction(c -> {
            watchlist.remove(c, userId, stockId);
            return null;
        });
    }
}
