package com.stocksim.exception;

/** A trade that cannot be executed; the message is safe to show on screen. */
public class InvalidQuantityException extends TradeException {
    public InvalidQuantityException(String message) {
        super(message);
    }
}
