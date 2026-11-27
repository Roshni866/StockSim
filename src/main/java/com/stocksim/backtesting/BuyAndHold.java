package com.stocksim.backtesting;

/** Buy at the first opportunity and keep the shares. */
public class BuyAndHold implements Strategy {
    @Override
    public int warmup() {
        return 1;
    }

    @Override
    public Signal decide(double[] history, boolean holding) {
        return holding ? Signal.HOLD : Signal.BUY;
    }
}
