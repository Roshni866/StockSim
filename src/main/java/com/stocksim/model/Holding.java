package com.stocksim.model;

import com.stocksim.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shares owned in one stock. The exact cost basis is stored; the average is derived. */
public class Holding implements Calculable {
    private final Stock stock;
    private final int quantity;
    private final BigDecimal costBasis;
    private final BigDecimal currentPrice;

    public Holding(Stock stock, int quantity, BigDecimal costBasis, BigDecimal currentPrice) {
        this.stock = stock;
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.currentPrice = currentPrice;
    }

    public Stock getStock() {
        return stock;
    }

    public String getSymbol() {
        return stock.getSymbol();
    }

    public String getSector() {
        return stock.getSector();
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public BigDecimal averagePrice() {
        return costBasis.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal marketValue() {
        return Money.times(currentPrice, quantity);
    }

    @Override
    public BigDecimal profitLoss() {
        return marketValue().subtract(costBasis);
    }

    public BigDecimal returnPercent() {
        return Money.percent(profitLoss(), costBasis);
    }
}
