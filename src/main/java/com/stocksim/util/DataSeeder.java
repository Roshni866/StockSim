package com.stocksim.util;

import com.stocksim.exception.DataAccessException;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Fills an empty stock_history table, from the configured CSV (or
 * data/stocks_df.csv in the project folder) if present, otherwise with deterministic SYNTHETIC data that the UI labels as such.
 * Simulation day N is the same calendar date for every stock.
 */
public final class DataSeeder {
    private static final Logger LOGGER = Logger.getLogger(DataSeeder.class.getName());
    public static final String SOURCE_CSV = "IMPORTED_CSV";
    public static final String SOURCE_SYNTHETIC = "SYNTHETIC";
    static final int SYNTHETIC_DAYS = 1500;
    /** Picked up automatically, so adding real data needs no configuration. */
    public static final Path DEFAULT_CSV = Path.of("data", "stocks_df.csv");

    private DataSeeder() {
    }

    public static void seedIfEmpty() {
        Database.transaction(conn -> {
            if (count(conn) > 0) {
                // Databases filled by the earlier version always came from the CSV.
                writeSource(conn, SOURCE_CSV, false);
                return null;
            }
            Map<String, Integer> ids = stockIds(conn);
            String configured = ConfigLoader.get("csv.path", "").trim();
            String path = configured.isEmpty() && Files.isReadable(DEFAULT_CSV) ? DEFAULT_CSV.toString()
                    : configured;
            Map<String, TreeMap<LocalDate, BigDecimal>> prices;
            String source;
            if (!path.isEmpty() && Files.isReadable(Path.of(path))) {
                prices = readCsv(Path.of(path), ids);
                source = SOURCE_CSV;
            } else {
                if (!path.isEmpty() && !path.equals("none")) {
                    LOGGER.warning("CSV not found at " + path + " - generating synthetic sample data.");
                }
                prices = synthetic(ids);
                source = SOURCE_SYNTHETIC;
            }
            insert(conn, ids, prices);
            writeSource(conn, source, true);
            return null;
        });
    }

    private static int count(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM stock_history")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void writeSource(Connection conn, String source, boolean overwrite) throws SQLException {
        String sql = overwrite
                ? "REPLACE INTO app_meta (meta_key, meta_value) VALUES ('data_source', ?)"
                : "INSERT IGNORE INTO app_meta (meta_key, meta_value) VALUES ('data_source', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.executeUpdate();
        }
    }

    private static Map<String, Integer> stockIds(Connection conn) throws SQLException {
        Map<String, Integer> ids = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT stock_id, symbol FROM stocks ORDER BY stock_id")) {
            while (rs.next()) {
                ids.put(rs.getString("symbol"), rs.getInt("stock_id"));
            }
        }
        return ids;
    }

    /** CSV layout: date,symbol,open,high,low,close,... */
    private static Map<String, TreeMap<LocalDate, BigDecimal>> readCsv(Path csv, Map<String, Integer> ids) {
        Map<String, TreeMap<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        ids.keySet().forEach(s -> prices.put(s, new TreeMap<>()));
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 7);
                if (parts.length < 6 || !prices.containsKey(parts[1])) {
                    continue;
                }
                try {
                    BigDecimal close = Money.of(new BigDecimal(parts[5]));
                    if (close.signum() > 0) {
                        prices.get(parts[1]).put(LocalDate.parse(parts[0]), close);
                    }
                } catch (NumberFormatException | DateTimeParseException e) {
                    LOGGER.fine("Skipped malformed CSV row: " + line);
                }
            }
        } catch (IOException e) {
            throw new DataAccessException("Could not read the price CSV " + csv + ": " + e.getMessage(), e);
        }
        return prices;
    }

    /** Illustrative only: market factor + sector factor + stock noise, fixed seed. */
    private static Map<String, TreeMap<LocalDate, BigDecimal>> synthetic(Map<String, Integer> ids) {
        Random random = new Random(20160104L);
        List<String> symbols = new ArrayList<>(ids.keySet());
        double[] price = new double[symbols.size()];
        Map<String, TreeMap<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        for (int i = 0; i < symbols.size(); i++) {
            price[i] = 150 + random.nextInt(1850);
            prices.put(symbols.get(i), new TreeMap<>());
        }
        LocalDate date = LocalDate.of(2016, 1, 4);
        for (int day = 0; day < SYNTHETIC_DAYS; date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            double market = 0.0003 + random.nextGaussian() * 0.008;
            double[] sector = new double[5];
            for (int s = 0; s < sector.length; s++) {
                sector[s] = random.nextGaussian() * 0.006;
            }
            for (int i = 0; i < symbols.size(); i++) {
                double move = market + sector[sectorIndex(symbols.get(i))] + random.nextGaussian() * 0.009;
                price[i] = Math.max(5, price[i] * (1 + move));
                prices.get(symbols.get(i)).put(date,
                        BigDecimal.valueOf(price[i]).setScale(2, RoundingMode.HALF_UP));
            }
            day++;
        }
        return prices;
    }

    private static int sectorIndex(String symbol) {
        return switch (symbol) {
            case "TCS", "INFY", "WIPRO" -> 0;
            case "HDFCBANK", "ICICIBANK", "SBIN" -> 1;
            case "ITC", "HINDUNILVR" -> 2;
            case "TATAMOTORS" -> 3;
            default -> 4;
        };
    }

    /** Keeps only dates every stock traded on and numbers them 1..N. */
    private static void insert(Connection conn, Map<String, Integer> ids,
                               Map<String, TreeMap<LocalDate, BigDecimal>> prices) throws SQLException {
        List<String> missing = ids.keySet().stream().filter(symbol -> prices.get(symbol).isEmpty()).toList();
        if (!missing.isEmpty()) {
            throw new SQLException("The price file has no usable rows for: " + String.join(", ", missing)
                    + ". Expected columns date,symbol,open,high,low,close with dates as YYYY-MM-DD.");
        }
        TreeSet<LocalDate> common = null;
        for (TreeMap<LocalDate, BigDecimal> series : prices.values()) {
            if (common == null) {
                common = new TreeSet<>(series.keySet());
            } else {
                common.retainAll(series.keySet());
            }
        }
        if (common == null || common.size() < 2) {
            throw new SQLException("The price file has no trading dates shared by all stocks.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO stock_history (stock_id, price, recorded_at, simulation_day) VALUES (?,?,?,?)")) {
            for (Map.Entry<String, Integer> stock : ids.entrySet()) {
                int day = 1;
                for (LocalDate date : common) {
                    ps.setInt(1, stock.getValue());
                    ps.setBigDecimal(2, prices.get(stock.getKey()).get(date));
                    ps.setDate(3, Date.valueOf(date));
                    ps.setInt(4, day++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        LOGGER.info("Imported " + common.size() + " trading days for " + ids.size() + " stocks.");
    }
}
