package com.stocksim.model;

/** Anything with a database identity and a display name. */
public abstract class FinancialEntity {
    private final int id;
    private final String name;

    protected FinancialEntity(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** One-line description; each subclass supplies its own (polymorphism). */
    public abstract String describe();

    @Override
    public boolean equals(Object other) {
        return this == other || other != null && getClass() == other.getClass() && ((FinancialEntity) other).id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return describe();
    }
}
