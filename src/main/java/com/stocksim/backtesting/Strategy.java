package com.stocksim.backtesting;

/**
 * A trading rule. It is only ever handed the prices known up to the decision
 * day, so it cannot look ahead.
 */
public interface Strategy {

    enum Signal {
        BUY, SELL, HOLD
    }

    /** Number of prices needed before the first decision. */
    int warmup();

    /**
     * @param history closes up to and including the decision day, oldest first
     * @param holding whether shares are currently held
     */
    Signal decide(double[] history, boolean holding);
}
