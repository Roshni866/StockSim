package com.stocksim.exception;

/** Base class for rejected trades. */
public class TradeException extends Exception {
    public TradeException(String message) {
        super(message);
    }
}
