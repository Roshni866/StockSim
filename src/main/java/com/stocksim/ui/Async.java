package com.stocksim.ui;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.concurrent.Task;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs database work away from the JavaFX Application Thread and delivers the
 * result back on it. One worker thread keeps operations in the order requested.
 * Must be called from the JavaFX thread.
 */
public final class Async {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stocksim-worker");
        t.setDaemon(true);
        return t;
    });
    private static final ReadOnlyIntegerWrapper PENDING = new ReadOnlyIntegerWrapper();

    private Async() {
    }

    /** Number of tasks not yet delivered. */
    public static ReadOnlyIntegerProperty pending() {
        return PENDING.getReadOnlyProperty();
    }

    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> deliver(() -> onSuccess.accept(task.getValue()), onError));
        task.setOnFailed(e -> deliver(() -> onError.accept(task.getException()), onError));
        PENDING.set(PENDING.get() + 1);
        WORKER.execute(task);
    }

    private static void deliver(Runnable action, Consumer<Throwable> onError) {
        try {
            action.run();
        } catch (RuntimeException e) {
            onError.accept(e);
        } finally {
            PENDING.set(PENDING.get() - 1);
        }
    }
}
