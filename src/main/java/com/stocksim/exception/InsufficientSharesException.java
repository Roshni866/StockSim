package com.stocksim.exception;

/** A trade that cannot be executed; the message is safe to show on screen. */
public class InsufficientSharesException extends TradeException {
    public InsufficientSharesException(String message) {
        super(message);
    }
}
