package com.stocksim.backtesting;

import com.stocksim.analytics.Indicators;
import com.stocksim.exception.ValidationException;

/** Buy when the lookback return reaches the entry level; sell when it falls to the exit level. */
public class MomentumStrategy implements Strategy {
    private final int lookback;
    private final double entry;
    private final double exit;

    public MomentumStrategy(int lookback, double entryPercent, double exitPercent) {
        if (lookback < 1 || lookback > 400) {
            throw new ValidationException("The momentum lookback must be between 1 and 400 days.");
        }
        if (exitPercent >= entryPercent) {
            throw new ValidationException("The exit threshold must be lower than the entry threshold.");
        }
        this.lookback = lookback;
        this.entry = entryPercent;
        this.exit = exitPercent;
    }

    @Override
    public int warmup() {
        return lookback + 1;
    }

    @Override
    public Signal decide(double[] history, boolean holding) {
        double momentum = Indicators.change(history, lookback).orElse(0);
        if (!holding && momentum >= entry) {
            return Signal.BUY;
        }
        if (holding && momentum <= exit) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
