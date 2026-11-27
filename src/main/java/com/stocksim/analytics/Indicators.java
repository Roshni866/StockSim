package com.stocksim.analytics;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * Statistics over a price series (oldest first). Ratios and statistics use
 * double; money amounts elsewhere use BigDecimal. An empty result means there is
 * not enough history - nothing is guessed.
 */
public final class Indicators {
    public static final int TRADING_DAYS_PER_YEAR = 252;

    private Indicators() {
    }

    /** Percentage change over the last {@code days} trading days. */
    public static OptionalDouble change(double[] prices, int days) {
        if (days < 1 || prices.length <= days || prices[prices.length - 1 - days] == 0) {
            return OptionalDouble.empty();
        }
        double past = prices[prices.length - 1 - days];
        return OptionalDouble.of((prices[prices.length - 1] - past) / past * 100);
    }

    public static OptionalDouble movingAverage(double[] prices, int period) {
        if (period < 1 || prices.length < period) {
            return OptionalDouble.empty();
        }
        return Arrays.stream(prices, prices.length - period, prices.length).average();
    }

    /** Daily returns as fractions: p[i] / p[i-1] - 1. */
    public static double[] returns(double[] prices) {
        double[] result = new double[Math.max(0, prices.length - 1)];
        for (int i = 1; i < prices.length; i++) {
            result[i - 1] = prices[i - 1] == 0 ? 0 : prices[i] / prices[i - 1] - 1;
        }
        return result;
    }

    /** Annualised standard deviation of the last {@code window} daily returns, in percent. */
    public static OptionalDouble volatility(double[] prices, int window) {
        double[] r = returns(prices);
        if (window < 2 || r.length < window) {
            return OptionalDouble.empty();
        }
        double[] last = Arrays.copyOfRange(r, r.length - window, r.length);
        return OptionalDouble.of(standardDeviation(last) * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100);
    }

    /** Mean of the last {@code window} daily returns, in percent. */
    public static OptionalDouble averageReturn(double[] prices, int window) {
        double[] r = returns(prices);
        if (window < 1 || r.length < window) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Arrays.stream(r, r.length - window, r.length).average().orElse(0) * 100);
    }

    /** Sample standard deviation. */
    public static double standardDeviation(double[] values) {
        if (values.length < 2) {
            return 0;
        }
        double mean = Arrays.stream(values).average().orElse(0);
        double sum = 0;
        for (double v : values) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    /** Largest fall from a running peak, as a positive percentage. */
    public static double maxDrawdown(double[] values) {
        double worst = 0;
        for (double d : drawdowns(values)) {
            worst = Math.min(worst, d);
        }
        return -worst;
    }

    /** Fall from the running peak at every point, as zero or negative percentages. */
    public static double[] drawdowns(double[] values) {
        double[] result = new double[values.length];
        double peak = 0;
        for (int i = 0; i < values.length; i++) {
            peak = Math.max(peak, values[i]);
            result[i] = peak == 0 ? 0 : (values[i] - peak) / peak * 100;
        }
        return result;
    }

    /** Pearson correlation of two equally long return series. */
    public static OptionalDouble correlation(double[] a, double[] b, int minimumObservations) {
        if (a.length != b.length || a.length < Math.max(2, minimumObservations)) {
            return OptionalDouble.empty();
        }
        double meanA = Arrays.stream(a).average().orElse(0);
        double meanB = Arrays.stream(b).average().orElse(0);
        double cov = 0;
        double varA = 0;
        double varB = 0;
        for (int i = 0; i < a.length; i++) {
            cov += (a[i] - meanA) * (b[i] - meanB);
            varA += (a[i] - meanA) * (a[i] - meanA);
            varB += (b[i] - meanB) * (b[i] - meanB);
        }
        if (varA == 0 || varB == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(cov / Math.sqrt(varA * varB));
    }
}
