package com.stocksim.exception;

/** A trade that cannot be executed; the message is safe to show on screen. */
public class InsufficientBalanceException extends TradeException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
