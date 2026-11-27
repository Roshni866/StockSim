package com.stocksim.exception;

/** Rejected user input or a rule violation; the message is safe to show on screen. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
