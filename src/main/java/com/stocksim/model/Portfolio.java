package com.stocksim.model;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A consistent valuation of one user's wallet and holdings at a moment. */
public record Portfolio(BigDecimal cash, BigDecimal startingCapital, BigDecimal invested, BigDecimal holdingsValue,
                        BigDecimal totalValue, BigDecimal unrealized, BigDecimal realized, BigDecimal totalProfit,
                        BigDecimal returnPercent, List<Holding> holdings) {

    public Optional<Holding> best() {
        return holdings.stream().max(Comparator.comparing(Holding::returnPercent));
    }

    public Optional<Holding> worst() {
        return holdings.stream().min(Comparator.comparing(Holding::returnPercent));
    }
}
