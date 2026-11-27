package com.stocksim.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An account. The password hash is deliberately not part of this object. */
public record User(int userId, String name, String username, String email, BigDecimal balance,
                   BigDecimal startingCapital, LocalDateTime createdAt) {
}
