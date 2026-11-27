package com.stocksim.model;

import java.math.BigDecimal;

/** A listed company. Prices are per user and per simulation day, so they live in {@link Quote}. */
public class Stock extends FinancialEntity implements Tradable {
    private final String symbol;
    private final String sector;
    private final BigDecimal beta;

    public Stock(int id, String symbol, String companyName, String sector, BigDecimal beta) {
        super(id, companyName);
        this.symbol = symbol;
        this.sector = sector;
        this.beta = beta;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public String getSector() {
        return sector;
    }

    /** Sensitivity to market-wide moves inside hypothetical scenarios. */
    public BigDecimal getBeta() {
        return beta;
    }

    @Override
    public String describe() {
        return symbol + " — " + getName();
    }
}
