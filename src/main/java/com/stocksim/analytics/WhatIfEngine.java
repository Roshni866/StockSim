package com.stocksim.analytics;

import com.stocksim.exception.ValidationException;
import com.stocksim.model.Holding;
import com.stocksim.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies hypothetical price movements to a copy of a portfolio. This class has
 * no database access, so it cannot change anything real.
 */
public final class WhatIfEngine {
    private static final BigDecimal MIN_PERCENT = BigDecimal.valueOf(-100);
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(500);

    public enum Scope {
        STOCK, SECTOR, MARKET
    }

    /** One hypothetical movement. target is a symbol or sector name; ignored for MARKET. */
    public record Shock(Scope scope, String target, BigDecimal percent) {
        public Shock {
            if (percent == null || percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
                throw new ValidationException("The price change must be between -100% and +500%.");
            }
            if (scope != Scope.MARKET && (target == null || target.isBlank())) {
                throw new ValidationException("Choose which " + scope.name().toLowerCase() + " should move.");
            }
        }

        public boolean appliesTo(Holding h) {
            return switch (scope) {
                case MARKET -> true;
                case SECTOR -> h.getSector().equals(target);
                case STOCK -> h.getSymbol().equals(target);
            };
        }

        public String describe() {
            String name = switch (scope) {
                case MARKET -> "Whole market";
                case SECTOR -> target + " sector";
                case STOCK -> target;
            };
            return name + "  " + (percent.signum() > 0 ? "+" : "") + percent.toPlainString() + "%";
        }
    }

    /** Effect on one holding. */
    public record Line(String symbol, String sector, int quantity, BigDecimal price, BigDecimal newPrice,
                       BigDecimal before, BigDecimal after, BigDecimal appliedPercent) {
        public BigDecimal change() {
            return after.subtract(before);
        }
    }

    /** Value of a sector before and after. */
    public record SectorImpact(String sector, BigDecimal before, BigDecimal after) {
        public BigDecimal change() {
            return after.subtract(before);
        }
    }

    public record Outcome(BigDecimal cash, BigDecimal originalValue, BigDecimal hypotheticalValue,
                          BigDecimal change, BigDecimal changePercent, List<Line> lines,
                          List<SectorImpact> sectors, String summary) {
        public long affectedHoldings() {
            return lines.stream().filter(l -> l.appliedPercent().signum() != 0).count();
        }

        /** The sector holding the most value before the shock. */
        public Optional<SectorImpact> biggestExposure() {
            return sectors.stream().max(Comparator.comparing(SectorImpact::before));
        }
    }

    private WhatIfEngine() {
    }

    /**
     * Movements that hit the same holding are added together, and a price can
     * fall no further than zero. Portfolio values include unchanged cash.
     */
    public static Outcome run(BigDecimal cash, List<Holding> snapshot, List<Shock> shocks) {
        if (shocks.isEmpty()) {
            throw new ValidationException("Add at least one hypothetical movement first.");
        }
        List<Line> lines = new ArrayList<>();
        Map<String, BigDecimal[]> bySector = new LinkedHashMap<>();
        BigDecimal before = Money.of(cash);
        BigDecimal after = Money.of(cash);
        for (Holding h : snapshot) {
            BigDecimal pct = shocks.stream().filter(s -> s.appliesTo(h)).map(Shock::percent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).max(MIN_PERCENT);
            BigDecimal newPrice = h.getCurrentPrice().add(Money.percentOf(h.getCurrentPrice(), pct));
            BigDecimal oldValue = h.marketValue();
            BigDecimal newValue = Money.times(newPrice, h.getQuantity());
            lines.add(new Line(h.getSymbol(), h.getSector(), h.getQuantity(), h.getCurrentPrice(), newPrice,
                    oldValue, newValue, pct));
            BigDecimal[] totals = bySector.computeIfAbsent(h.getSector(),
                    k -> new BigDecimal[] {Money.ZERO, Money.ZERO});
            totals[0] = totals[0].add(oldValue);
            totals[1] = totals[1].add(newValue);
            before = before.add(oldValue);
            after = after.add(newValue);
        }
        lines.sort(Comparator.comparing(Line::change));
        List<SectorImpact> sectors = bySector.entrySet().stream()
                .map(e -> new SectorImpact(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        BigDecimal change = after.subtract(before);
        BigDecimal changePercent = Money.percent(change, before);
        return new Outcome(Money.of(cash), before, after, change, changePercent, List.copyOf(lines), sectors,
                summarise(snapshot, lines, change, changePercent));
    }

    private static String summarise(List<Holding> snapshot, List<Line> lines, BigDecimal change, BigDecimal pct) {
        if (snapshot.isEmpty()) {
            return "You hold no shares, so these movements would not change your portfolio value.";
        }
        long affected = lines.stream().filter(l -> l.appliedPercent().signum() != 0).count();
        if (affected == 0) {
            return "None of your holdings are affected by these movements.";
        }
        Line biggest = lines.stream().max(Comparator.comparing(l -> l.change().abs())).orElseThrow();
        return String.format("Hypothetically, %d of %d holdings move and total portfolio value %s by %s (%s%%). "
                        + "The largest single effect comes from %s.", affected, lines.size(),
                change.signum() < 0 ? "falls" : "rises", com.stocksim.util.Formats.money(change.abs()),
                pct.abs().toPlainString(), biggest.symbol());
    }
}
