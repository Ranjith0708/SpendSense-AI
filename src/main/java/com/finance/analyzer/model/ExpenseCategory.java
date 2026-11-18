package com.finance.analyzer.model;

public enum ExpenseCategory {
    HOUSING_AND_RENT("Housing & Rent", "#4F46E5"),
    UTILITIES_AND_BILLS("Utilities & Bills", "#0891B2"),
    GROCERIES_AND_HOUSEHOLD("Groceries & Household", "#16A34A"),
    FOOD_AND_DINING("Food & Dining", "#EA580C"),
    TRANSPORTATION_AND_FUEL("Transportation & Fuel", "#CA8A04"),
    SHOPPING_AND_PERSONAL_CARE("Shopping & Personal Care", "#DB2777"),
    HEALTH_AND_MEDICAL("Health & Medical", "#DC2626"),
    ENTERTAINMENT_AND_SUBSCRIPTIONS("Entertainment & Subscriptions", "#9333EA"),
    EDUCATION_AND_LEARNING("Education & Learning", "#2563EB"),
    TRAVEL_AND_VACATIONS("Travel & Vacations", "#0D9488"),
    DEBT_AND_EMIS("Debt & EMIs", "#7F1D1D"),
    INVESTMENTS_AND_SAVINGS("Investments & Savings", "#15803D"),
    INCOME_AND_TRANSFERS("Income & Transfers", "#64748B"),
    OTHER_UNCATEGORIZED("Other / Uncategorized", "#94A3B8");

    private final String label;
    private final String color;

    ExpenseCategory(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() { return label; }
    public String getColor() { return color; }
}
