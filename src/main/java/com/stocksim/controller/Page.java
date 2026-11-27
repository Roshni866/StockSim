package com.stocksim.controller;

/** The workspaces reachable from the application shell. */
public enum Page {
    OVERVIEW("Overview", "overview"),
    MARKET("Market Terminal", "market"),
    PORTFOLIO("Portfolio", "portfolio"),
    LAB("Simulation Lab", "lab"),
    RISK("Risk Analytics", "risk"),
    PERFORMANCE("Performance", "performance"),
    TRANSACTIONS("Transactions", "transactions"),
    WATCHLIST("Watchlist", "watchlist"),
    GOALS("Goals & Alerts", "goals"),
    SETTINGS("Settings", "settings"),
    STOCK("Stock Analysis", "stock");

    private final String title;
    private final String fxml;

    Page(String title, String fxml) {
        this.title = title;
        this.fxml = fxml;
    }

    public String title() {
        return title;
    }

    public String fxml() {
        return fxml;
    }
}
