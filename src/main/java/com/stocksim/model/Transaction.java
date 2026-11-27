package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** An executed trade in the user's ledger. realizedProfit is null for purchases. */
public record Transaction(int id, int stockId, String symbol, String companyName, TradeType type, int quantity,
                          BigDecimal price, BigDecimal total, BigDecimal realizedProfit,
                          LocalDateTime createdAt, int simulationDay, LocalDate marketDate) {
}
