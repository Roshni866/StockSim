package com.stocksim.service;

import com.stocksim.dao.GoalDAO;
import com.stocksim.exception.ValidationException;
import com.stocksim.model.InvestmentGoal;
import com.stocksim.util.Database;
import com.stocksim.util.Money;
import com.stocksim.util.Validator;

import java.math.BigDecimal;
import java.util.List;

/** Investment goals measured against total portfolio value (cash plus holdings). */
public class GoalService {
    private final GoalDAO goals = new GoalDAO();
    private final PortfolioService portfolios = new PortfolioService();

    public List<InvestmentGoal> list(int userId) {
        BigDecimal value = portfolios.getPortfolio(userId).totalValue();
        return Database.read(c -> goals.findByUser(c, userId)).stream()
                .map(g -> new InvestmentGoal(g.id(), g.name(), g.target(), g.createdAt(), value,
                        Money.percent(value, g.target())))
                .toList();
    }

    public void create(int userId, String name, BigDecimal target) {
        String clean = requireName(name);
        BigDecimal amount = Validator.requireAmount(target, "Target amount");
        Database.transaction(c -> goals.insert(c, userId, clean, amount));
    }

    public void update(int userId, int goalId, String name, BigDecimal target) {
        String clean = requireName(name);
        BigDecimal amount = Validator.requireAmount(target, "Target amount");
        Database.transaction(c -> {
            if (!goals.update(c, userId, goalId, clean, amount)) {
                throw new ValidationException("Goal not found.");
            }
            return null;
        });
    }

    public void delete(int userId, int goalId) {
        Database.transaction(c -> {
            if (!goals.delete(c, userId, goalId)) {
                throw new ValidationException("Goal not found.");
            }
            return null;
        });
    }

    private static String requireName(String name) {
        if (!Validator.isNotEmpty(name) || name.trim().length() > 100) {
            throw new ValidationException("Give the goal a name of up to 100 characters.");
        }
        return name.trim();
    }
}
