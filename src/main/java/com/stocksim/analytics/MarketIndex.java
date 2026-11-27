package com.stocksim.analytics;

import com.stocksim.model.StockPrice;

import java.util.Collection;
import java.util.List;

/** Equal-weighted index: 1000 x the average of each stock's price relative to the first day. */
public final class MarketIndex {
    public static final double BASE = 1000;

    private MarketIndex() {
    }

    /** All series must cover the same days. */
    public static double[] of(Collection<List<StockPrice>> series) {
        if (series.isEmpty()) {
            return new double[0];
        }
        int length = series.iterator().next().size();
        double[] index = new double[length];
        for (List<StockPrice> s : series) {
            double base = s.get(0).price().doubleValue();
            for (int i = 0; i < length; i++) {
                index[i] += BASE * s.get(i).price().doubleValue() / base / series.size();
            }
        }
        return index;
    }
}
