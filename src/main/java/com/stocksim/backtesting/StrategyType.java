package com.stocksim.backtesting;

/** The strategies that can be backtested. */
public enum StrategyType {
    BUY_AND_HOLD("Buy and Hold"),
    MOVING_AVERAGE("Moving Average Crossover"),
    MOMENTUM("Momentum");

    private final String label;

    StrategyType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
