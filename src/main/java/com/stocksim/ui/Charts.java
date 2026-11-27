package com.stocksim.ui;

import com.stocksim.util.Formats;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fills JavaFX charts. Every empty data set shows an explanation instead. */
public final class Charts {

    private Charts() {
    }

    /** One line: y values aligned with the chart's dates. */
    public record Line(String name, List<Double> values) {
    }

    /** Draws lines against dates. */
    public static void lines(Pane holder, String yLabel, List<LocalDate> dates, Line... lines) {
        if (dates.size() < 2) {
            empty(holder, "Not enough history yet. Advance the simulation to draw this chart.");
            return;
        }
        holder.getChildren().setAll(lineChart(yLabel, dates, lines));
    }

    public static LineChart<Number, Number> lineChart(String yLabel, List<LocalDate> dates, Line... lines) {
        // Evenly spaced date labels, so the last one never collides with its neighbour.
        NumberAxis x = new NumberAxis(0, dates.size() - 1, Math.max(1, (dates.size() - 1) / 4.0));
        x.setMinorTickVisible(false);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                int i = (int) Math.round(n.doubleValue());
                return i >= 0 && i < dates.size() ? Formats.shortDate(dates.get(i)) : "";
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(false);
        y.setLabel(yLabel);
        y.setMinorTickVisible(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(lines.length > 1);
        for (Line line : lines) {
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(line.name());
            for (int i = 0; i < line.values().size(); i++) {
                if (line.values().get(i) != null) {
                    series.getData().add(new XYChart.Data<>(i, line.values().get(i)));
                }
            }
            chart.getData().add(series);
        }
        return chart;
    }

    /** One bar per category, green when positive and red when negative. */
    public static void signedBars(Pane holder, String yLabel, Map<String, BigDecimal> values, String whenEmpty) {
        if (values.isEmpty()) {
            empty(holder, whenEmpty);
            return;
        }
        BarChart<String, Number> chart = barChart(yLabel);
        chart.setLegendVisible(false);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        values.forEach((name, value) -> {
            series.getData().add(new XYChart.Data<>(name, value.doubleValue()));
        });
        chart.getData().add(series);
        // The chart assigns its own style classes when it creates bars, so ours are added afterwards.
        for (XYChart.Data<String, Number> data : series.getData()) {
            Node node = data.getNode();
            boolean negative = data.getYValue().doubleValue() < 0;
            node.getStyleClass().add(negative ? "bar-negative" : "bar-positive");
            Tooltip.install(node, new Tooltip(data.getXValue() + ": " + values.get(data.getXValue()).toPlainString()));
        }
        holder.getChildren().setAll(chart);
    }

    /** Two bars per category, for before-and-after comparisons. */
    public static void comparisonBars(Pane holder, String yLabel, List<String> categories, String firstName,
                                      List<BigDecimal> first, String secondName, List<BigDecimal> second) {
        BarChart<String, Number> chart = barChart(yLabel);
        for (int s = 0; s < 2; s++) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(s == 0 ? firstName : secondName);
            for (int i = 0; i < categories.size(); i++) {
                series.getData().add(new XYChart.Data<>(categories.get(i), (s == 0 ? first : second).get(i)));
            }
            chart.getData().add(series);
        }
        holder.getChildren().setAll(chart);
    }

    private static BarChart<String, Number> barChart(String yLabel) {
        javafx.scene.chart.CategoryAxis x = new javafx.scene.chart.CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setMinorTickVisible(false);
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setCategoryGap(14);
        return chart;
    }

    /** A pie whose legend carries each slice's share. values are percentages or amounts. */
    public static void pie(Pane holder, Map<String, BigDecimal> values, String whenEmpty) {
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            empty(holder, whenEmpty);
            return;
        }
        PieChart chart = new PieChart();
        chart.setAnimated(false);
        chart.setLabelsVisible(false);
        chart.setStartAngle(90);
        chart.setLegendSide(javafx.geometry.Side.RIGHT);
        List<PieChart.Data> slices = new ArrayList<>();
        values.forEach((name, value) -> {
            if (value.signum() > 0) {
                slices.add(new PieChart.Data(name + "  " + com.stocksim.util.Money.percent(value, total) + "%",
                        value.doubleValue()));
            }
        });
        chart.getData().setAll(slices);
        for (PieChart.Data slice : slices) {
            Node node = slice.getNode();
            if (node != null) {
                Tooltip.install(node, new Tooltip(slice.getName()));
            }
        }
        holder.getChildren().setAll(chart);
    }

    public static void empty(Pane holder, String message) {
        javafx.scene.control.Label label = Ui.note(message);
        label.setMaxWidth(360);
        label.getStyleClass().add("empty-state");
        holder.getChildren().setAll(label);
    }
}
