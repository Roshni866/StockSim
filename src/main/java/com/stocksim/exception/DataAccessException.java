package com.stocksim.exception;

/** Unchecked wrapper for database and file failures. */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
