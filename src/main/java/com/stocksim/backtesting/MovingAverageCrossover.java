package com.stocksim.backtesting;

import com.stocksim.analytics.Indicators;
import com.stocksim.exception.ValidationException;

/** Buy when the short average crosses above the long one; sell when it crosses below. */
public class MovingAverageCrossover implements Strategy {
    private final int shortPeriod;
    private final int longPeriod;

    public MovingAverageCrossover(int shortPeriod, int longPeriod) {
        if (shortPeriod < 2 || longPeriod <= shortPeriod || longPeriod > 400) {
            throw new ValidationException("Use a short period of at least 2 and a longer long period (up to 400).");
        }
        this.shortPeriod = shortPeriod;
        this.longPeriod = longPeriod;
    }

    @Override
    public int warmup() {
        return longPeriod + 1;
    }

    @Override
    public Signal decide(double[] history, boolean holding) {
        double[] yesterday = java.util.Arrays.copyOf(history, history.length - 1);
        double before = average(yesterday, shortPeriod) - average(yesterday, longPeriod);
        double now = average(history, shortPeriod) - average(history, longPeriod);
        if (!holding && before <= 0 && now > 0) {
            return Signal.BUY;
        }
        if (holding && before >= 0 && now < 0) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }

    private static double average(double[] values, int period) {
        return Indicators.movingAverage(values, period).orElseThrow();
    }
}
