package com.stocksim;

import com.stocksim.exception.DataAccessException;
import com.stocksim.util.ConfigLoader;
import com.stocksim.util.DataSeeder;
import com.stocksim.util.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static com.stocksim.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Importing a price file into an empty database. The file here is made-up test input. */
class DataImportTest {
    private static final List<String> SYMBOLS = List.of("TCS", "INFY", "RELIANCE", "HDFCBANK", "ITC", "WIPRO",
            "ICICIBANK", "TATAMOTORS", "SBIN", "HINDUNILVR");
    private static final int DAYS = 3500;

    @TempDir
    Path folder;

    @Test
    void importsSharedDatesOnlyAndNamesMissingStocks() throws Exception {
        database();
        String testUrl = System.getProperty("db.url");
        String csvSetting = System.getProperty("csv.path");
        String importUrl = testUrl.replace("/stocksim_test", "/stocksim_test_import")
                + "&rewriteBatchedStatements=true";
        System.setProperty("db.url", importUrl);
        try {
            drop(importUrl);
            SchemaMigrator.migrate();

            System.setProperty("csv.path", write("incomplete.csv", SYMBOLS.subList(0, 9)).toString());
            DataAccessException error = assertThrows(DataAccessException.class, DataSeeder::seedIfEmpty);
            assertTrue(error.getMessage().contains("HINDUNILVR"), error.getMessage());
            assertEquals(0L, scalar("SELECT COUNT(*) FROM stock_history"));

            System.setProperty("csv.path", write("complete.csv", SYMBOLS).toString());
            DataSeeder.seedIfEmpty();
            assertEquals(DataSeeder.SOURCE_CSV,
                    scalar("SELECT meta_value FROM app_meta WHERE meta_key = 'data_source'"));
            // One date is missing for TCS, so it is dropped for every stock.
            assertEquals((long) (DAYS - 1) * SYMBOLS.size(), scalar("SELECT COUNT(*) FROM stock_history"));
            assertEquals(DAYS - 1, scalar("SELECT MAX(simulation_day) FROM stock_history"));
        } finally {
            drop(importUrl);
            System.setProperty("db.url", testUrl);
            System.setProperty("csv.path", csvSetting);
        }
    }

    private Path write(String name, List<String> symbols) throws IOException {
        Path file = folder.resolve(name);
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("Date,Symbol,Open,High,Low,Close,Adj Close,Volume\n");
            for (int day = 0; day < DAYS; day++) {
                for (String symbol : symbols) {
                    if (day == 100 && symbol.equals("TCS")) {
                        continue;
                    }
                    out.write(LocalDate.of(2008, 1, 1).plusDays(day) + "," + symbol + ",100,101,99,"
                            + (100 + day % 50) + ",100,1000\n");
                }
            }
            out.write("not-a-date,TCS,1,1,1,1,1,1\nshort,row\n");
        }
        return file;
    }

    private static void drop(String url) throws SQLException {
        String server = url.replace("/stocksim_test_import", "/");
        try (Connection c = DriverManager.getConnection(server, ConfigLoader.get("db.user", "root"),
                ConfigLoader.get("db.password", "")); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS stocksim_test_import");
        }
    }
}
