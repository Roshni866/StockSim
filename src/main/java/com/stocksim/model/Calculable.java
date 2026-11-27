package com.stocksim.model;

import java.math.BigDecimal;

/** A position whose value and profit can be calculated. */
public interface Calculable {
    BigDecimal marketValue();

    BigDecimal profitLoss();
}
