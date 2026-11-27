package com.stocksim.model;

/** Per-user preferences. */
public record UserSettings(boolean confirmTrades, String exportDir, int seenAlertDay) {
}
