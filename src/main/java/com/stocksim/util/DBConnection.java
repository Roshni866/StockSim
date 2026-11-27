package com.stocksim.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Hands out JDBC connections; each caller closes its own (try-with-resources). */
public final class DBConnection {
    private DBConnection() {
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(
                ConfigLoader.get("db.url", ""),
                ConfigLoader.get("db.user", "root"),
                ConfigLoader.get("db.password", ""));
    }
}
