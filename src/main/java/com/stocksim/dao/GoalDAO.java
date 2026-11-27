package com.stocksim.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** SQL for investment goals. Updates and deletes are always scoped to the owner. */
public class GoalDAO extends BaseDAO {

    /** The stored part of a goal. */
    public record Row(int id, String name, BigDecimal target, LocalDateTime createdAt) {
    }

    public int insert(Connection c, int userId, String name, BigDecimal target) throws SQLException {
        return insert(c, "INSERT INTO goals (user_id, goal_name, target_amount) VALUES (?,?,?)", userId, name, target);
    }

    public List<Row> findByUser(Connection c, int userId) throws SQLException {
        return query(c, rs -> new Row(rs.getInt("goal_id"), rs.getString("goal_name"),
                        rs.getBigDecimal("target_amount"), rs.getTimestamp("created_at").toLocalDateTime()),
                "SELECT * FROM goals WHERE user_id = ? ORDER BY goal_id", userId);
    }

    public boolean update(Connection c, int userId, int goalId, String name, BigDecimal target) throws SQLException {
        return update(c, "UPDATE goals SET goal_name = ?, target_amount = ? WHERE goal_id = ? AND user_id = ?",
                name, target, goalId, userId) == 1;
    }

    public boolean delete(Connection c, int userId, int goalId) throws SQLException {
        return update(c, "DELETE FROM goals WHERE goal_id = ? AND user_id = ?", goalId, userId) == 1;
    }
}
