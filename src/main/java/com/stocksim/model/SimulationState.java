package com.stocksim.model;

import java.io.Serial;
import java.io.Serializable;

/** Where a user's simulation clock stands. activeRunId is null when no scenario runs. */
public record SimulationState(int userId, int startDay, int currentDay, Integer activeRunId) implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;
}
