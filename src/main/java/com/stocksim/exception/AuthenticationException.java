package com.stocksim.exception;

/** Login failed. */
public class AuthenticationException extends Exception {
    public AuthenticationException(String message) {
        super(message);
    }
}
