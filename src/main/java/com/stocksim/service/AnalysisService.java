package com.stocksim.service;

import com.stocksim.analytics.Indicators;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.Stock;
import com.stocksim.model.StockAnalysis;
import com.stocksim.model.StockPrice;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/** Describes past behaviour of a stock. Nothing here predicts future prices. */
public class AnalysisService {
    public static final int VOLATILITY_WINDOW = 30;
    public static final int MOMENTUM_WINDOW = 10;
    private final MarketService market = new MarketService();

    /** Chart ranges, in trading days of price points. */
    public enum Period {
        ONE_DAY("1D", 2), ONE_WEEK("1W", 6), ONE_MONTH("1M", 22), SIX_MONTHS("6M", 127);

        private final String label;
        private final int points;

        Period(String label, int points) {
            this.label = label;
            this.points = points;
        }

        public int points() {
            return points;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public StockAnalysis analyse(int userId, int stockId) {
        Stock stock = market.stocks().stream().filter(s -> s.getId() == stockId).findFirst()
                .orElseThrow(() -> new ValidationException("That stock does not exist."));
        return analyse(stock, market.series(userId, stockId, Indicators.TRADING_DAYS_PER_YEAR));
    }

    /** Calculates indicators from up to a year of prices, oldest first. */
    public static StockAnalysis analyse(Stock stock, List<StockPrice> series) {
        double[] p = series.stream().mapToDouble(x -> x.price().doubleValue()).toArray();
        BigDecimal price = series.get(series.size() - 1).price();
        BigDecimal previous = series.size() > 1 ? series.get(series.size() - 2).price() : price;
        OptionalDouble ma5 = Indicators.movingAverage(p, 5);
        OptionalDouble ma20 = Indicators.movingAverage(p, 20);
        OptionalDouble volatility = Indicators.volatility(p, VOLATILITY_WINDOW);
        OptionalDouble momentum = Indicators.change(p, MOMENTUM_WINDOW);
        return new StockAnalysis(stock, price, price.subtract(previous), number(Indicators.change(p, 1)),
                number(Indicators.change(p, 7)), number(Indicators.change(p, 30)), number(ma5), number(ma20),
                Money.of(Arrays.stream(p).max().orElse(0)), Money.of(Arrays.stream(p).min().orElse(0)),
                number(volatility), number(Indicators.averageReturn(p, VOLATILITY_WINDOW)), number(momentum),
                trend(price.doubleValue(), ma5, ma20), momentumLabel(momentum), riskLabel(volatility), p.length);
    }

    /** Uptrend when price and the 5-day average are both above the 20-day average. */
    static String trend(double price, OptionalDouble ma5, OptionalDouble ma20) {
        if (ma5.isEmpty() || ma20.isEmpty()) {
            return "Insufficient data";
        }
        if (price > ma20.getAsDouble() && ma5.getAsDouble() > ma20.getAsDouble()) {
            return "Uptrend";
        }
        if (price < ma20.getAsDouble() && ma5.getAsDouble() < ma20.getAsDouble()) {
            return "Downtrend";
        }
        return "Sideways / mixed";
    }

    static String momentumLabel(OptionalDouble momentum) {
        if (momentum.isEmpty()) {
            return "Insufficient data";
        }
        double m = momentum.getAsDouble();
        if (m >= 5) {
            return "Strong positive";
        }
        if (m >= 1) {
            return "Positive";
        }
        if (m <= -5) {
            return "Strong negative";
        }
        return m <= -1 ? "Negative" : "Flat";
    }

    /** Based on annualised volatility: under 20% low, under 35% moderate, otherwise high. */
    static String riskLabel(OptionalDouble volatility) {
        if (volatility.isEmpty()) {
            return "Insufficient data";
        }
        double v = volatility.getAsDouble();
        return v < 20 ? "Lower volatility" : v < 35 ? "Moderate volatility" : "High volatility";
    }

    private static BigDecimal number(OptionalDouble value) {
        return value.isPresent() ? Money.of(value.getAsDouble()) : null;
    }
}
