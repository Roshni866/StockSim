package com.stocksim.service;

import com.stocksim.analytics.WhatIfEngine;
import com.stocksim.model.Portfolio;

import java.util.List;

/**
 * Hypothetical experiments on a temporary copy of the portfolio. It only ever
 * reads from the database.
 */
public class WhatIfService {
    private final PortfolioService portfolio = new PortfolioService();

    public Portfolio snapshot(int userId) {
        return portfolio.getPortfolio(userId);
    }

    public WhatIfEngine.Outcome run(Portfolio snapshot, List<WhatIfEngine.Shock> shocks) {
        return WhatIfEngine.run(snapshot.cash(), snapshot.holdings(), shocks);
    }
}
