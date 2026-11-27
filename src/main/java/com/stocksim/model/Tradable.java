package com.stocksim.model;

/** Something that can be bought and sold in the simulator. */
public interface Tradable {
    String getSymbol();

    String getSector();
}
