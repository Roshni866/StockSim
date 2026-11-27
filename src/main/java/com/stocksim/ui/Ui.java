package com.stocksim.ui;

import com.stocksim.util.Formats;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.function.Function;

/** Small reusable building blocks shared by every screen. */
public final class Ui {

    private Ui() {
    }

    public static String stylesheet() {
        return Ui.class.getResource("/styles/main.css").toExternalForm();
    }

    public static Label label(String text, String... styles) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styles);
        return label;
    }

    /** A label that wraps instead of being cut off. */
    public static Label note(String text, String... styles) {
        Label label = label(text, styles);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (styles.length == 0) {
            label.getStyleClass().add("muted");
        }
        return label;
    }

    public static Button button(String text, Runnable action, String... styles) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styles);
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Replaces the tiles in a metric strip. */
    public static void metrics(Pane strip, Node... tiles) {
        strip.getChildren().setAll(tiles);
    }

    public static VBox metric(String caption, String value) {
        return metric(caption, value, null, null);
    }

    public static VBox metric(String caption, String value, String valueStyle, String hint) {
        Label number = label(value, "metric-value");
        if (valueStyle != null) {
            number.getStyleClass().add(valueStyle);
        }
        VBox tile = new VBox(2, label(caption.toUpperCase(), "metric-caption"), number);
        tile.getStyleClass().add("metric");
        if (hint != null) {
            Label text = note(hint, "metric-hint");
            text.setMaxWidth(230);
            tile.getChildren().add(text);
        }
        return tile;
    }

    public static VBox money(String caption, BigDecimal value) {
        return metric(caption, Formats.money(value));
    }

    public static VBox profit(String caption, BigDecimal value) {
        return metric(caption, Formats.signedMoney(value), Formats.signClass(value), null);
    }

    public static VBox change(String caption, BigDecimal percent) {
        return metric(caption, Formats.signedPercent(percent), Formats.signClass(percent), null);
    }

    /** A caption/value pair added as the next row of a grid. */
    public static void row(GridPane grid, String caption, String value, String... valueStyles) {
        int row = grid.getRowCount();
        Label text = label(value, valueStyles);
        text.setWrapText(true);
        grid.addRow(row, label(caption, "muted"), text);
    }

    public static <T> TableColumn<T, String> text(TableView<T> table, String title, double width,
                                                  Function<T, String> value) {
        return column(table, title, width, value, v -> v, null, Pos.CENTER_LEFT);
    }

    /** A right-aligned column that sorts by its real value, not its formatted text. */
    public static <T, V extends Comparable<? super V>> TableColumn<T, V> number(TableView<T> table, String title,
            double width, Function<T, V> value, Function<V, String> format) {
        return column(table, title, width, value, format, null, Pos.CENTER_RIGHT);
    }

    public static <T> TableColumn<T, BigDecimal> money(TableView<T> table, String title, double width,
                                                       Function<T, BigDecimal> value) {
        return number(table, title, width, value, Formats::money);
    }

    /** Signed money or percentage, coloured green or red. */
    public static <T> TableColumn<T, BigDecimal> signed(TableView<T> table, String title, double width,
            Function<T, BigDecimal> value, Function<BigDecimal, String> format) {
        return column(table, title, width, value, format, Formats::signClass, Pos.CENTER_RIGHT);
    }

    public static <T, V> TableColumn<T, V> column(TableView<T> table, String title, double width,
            Function<T, V> value, Function<V, String> format, Function<V, String> style, Pos alignment) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        // Columns shrink only a little, so values are not cut off; a table that cannot fit scrolls.
        column.setMinWidth(Math.round(width * 0.8));
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(value.apply(c.getValue())));
        column.setComparator(Comparator.nullsFirst((a, b) -> compare(a, b)));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(V item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("positive", "negative", "neutral");
                setAlignment(alignment);
                if (empty) {
                    setText(null);
                    return;
                }
                setText(item == null ? Formats.NONE : format.apply(item));
                if (item != null && style != null) {
                    getStyleClass().add(style.apply(item));
                }
            }
        });
        table.getColumns().add(column);
        return column;
    }

    @SuppressWarnings("unchecked")
    private static <V> int compare(V a, V b) {
        return a instanceof Comparable<?> ? ((Comparable<V>) a).compareTo(b) : a.toString().compareTo(b.toString());
    }

    /** Shared table setup: fill the width and explain why a table is empty. */
    public static <T> void table(TableView<T> table, String emptyMessage) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label empty = note(emptyMessage);
        empty.setMaxWidth(420);
        empty.setAlignment(Pos.CENTER);
        table.setPlaceholder(empty);
    }

    /** Replaces a table's rows while keeping the user's sort order and selection. */
    public static <T> void fill(TableView<T> table, java.util.List<T> rows) {
        T selected = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(rows);
        table.sort();
        if (selected != null && table.getItems().contains(selected)) {
            table.getSelectionModel().select(selected);
        }
    }
}
