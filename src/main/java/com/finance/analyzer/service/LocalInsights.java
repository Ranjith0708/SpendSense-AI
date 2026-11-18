package com.finance.analyzer.service;

import com.finance.analyzer.dto.AnalysisResponse.CategorySummary;
import com.finance.analyzer.dto.AnalysisResponse.Narrative;
import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Plain-Java summary, insights and tips computed from the same totals the model would see. Used when the model's
 * commentary is missing or unusable, so the Insights tab is never empty. Every figure here is exact.
 */
final class LocalInsights {

    private static final Set<ExpenseCategory> DISCRETIONARY = Set.of(
            ExpenseCategory.FOOD_AND_DINING, ExpenseCategory.SHOPPING_AND_PERSONAL_CARE,
            ExpenseCategory.ENTERTAINMENT_AND_SUBSCRIPTIONS, ExpenseCategory.TRAVEL_AND_VACATIONS);
    private static final double MICRO_LIMIT = 60;

    private LocalInsights() {}

    static Narrative build(double expense, double income, List<CategorySummary> breakdown, List<Transaction> spend) {
        if (spend.isEmpty() || breakdown.isEmpty()) {
            return new Narrative("No spending was found in this statement.", List.of(), List.of(), List.of());
        }
        CategorySummary top = breakdown.get(0);
        String summary = "You spent " + rs(expense) + " across " + spend.size() + " payments. "
                + top.displayLabel() + " was the biggest category at " + top.percentage() + "% of your spending."
                + (income > 0 ? " Credits received: " + rs(income) + "." : "");

        List<String> insights = new ArrayList<>();
        breakdown.stream().limit(3).forEach(c -> insights.add(c.displayLabel() + ": " + rs(c.totalAmount())
                + " (" + c.percentage() + "% of spending, " + c.transactionCount() + " payments)."));
        List<Group> repeats = groups(spend).stream().filter(g -> g.count >= 3).limit(3).toList();
        repeats.forEach(g -> insights.add("You paid " + g.name + " " + g.count + " times, " + rs(g.total) + " in total."));
        spend.stream().max(Comparator.comparingDouble(Transaction::amount)).ifPresent(t ->
                insights.add("Your largest single payment was " + rs(t.amount()) + " to " + shorten(t.details()) + "."));
        breakdown.stream().filter(c -> c.category() == ExpenseCategory.OTHER_UNCATEGORIZED).findFirst().ifPresent(c ->
                insights.add(c.transactionCount() + " payments (" + rs(c.totalAmount()) + ") are uncategorized. Check them in the Transactions tab."));

        List<String> feedback = new ArrayList<>();
        if (top.percentage() >= 40) {
            feedback.add(top.displayLabel() + " takes " + top.percentage() + "% of your spending. Set a monthly limit for it.");
        }
        List<Transaction> micro = spend.stream().filter(t -> t.amount() <= MICRO_LIMIT).toList();
        if (micro.size() >= 10) {
            feedback.add("You made " + micro.size() + " payments of " + rs(MICRO_LIMIT) + " or less, " + rs(sum(micro))
                    + " in total. Small payments are easy to lose track of.");
        }
        if (income > 0 && expense > income) {
            feedback.add("Spending was " + rs(expense - income) + " more than the credits in this statement.");
        }
        feedback.add("Review your top three categories once a week so nothing surprises you at month end.");

        List<String> tips = new ArrayList<>();
        breakdown.stream().filter(c -> DISCRETIONARY.contains(c.category())).limit(2).forEach(c ->
                tips.add("Trimming " + c.displayLabel() + " by 10% would save about " + rs(c.totalAmount() * 0.10) + " over a period like this one."));
        if (!repeats.isEmpty()) {
            Group g = repeats.get(0);
            tips.add("Combine your " + g.count + " payments to " + g.name + " (" + rs(g.total) + ") into fewer, larger top-ups or a pass.");
        }
        if (tips.isEmpty()) tips.add("Pick your largest category, " + top.displayLabel() + ", and aim to cut it by 10%.");

        return new Narrative(summary, insights, feedback, tips);
    }

    /** Uses {@code primary} where the model gave something, and {@code fallback} for anything it left blank. */
    static Narrative fillGaps(Narrative primary, Narrative fallback) {
        return new Narrative(
                primary.summary() == null || primary.summary().isBlank() ? fallback.summary() : primary.summary(),
                nonEmpty(primary.insights()) ? primary.insights() : fallback.insights(),
                nonEmpty(primary.feedback()) ? primary.feedback() : fallback.feedback(),
                nonEmpty(primary.savingsTips()) ? primary.savingsTips() : fallback.savingsTips());
    }

    private static boolean nonEmpty(List<String> l) { return l != null && !l.isEmpty(); }

    private record Group(String name, int count, double total) {}

    private static List<Group> groups(List<Transaction> spend) {
        Map<String, List<Transaction>> by = new LinkedHashMap<>();
        for (Transaction t : spend) {
            if (t.details() == null) continue;
            by.computeIfAbsent(t.details().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""), k -> new ArrayList<>()).add(t);
        }
        return by.values().stream()
                .map(g -> new Group(shorten(g.get(0).details()), g.size(), sum(g)))
                .sorted(Comparator.comparingInt(Group::count).reversed())
                .toList();
    }

    private static double sum(List<Transaction> l) { return l.stream().mapToDouble(Transaction::amount).sum(); }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) return "an unnamed payee";
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }

    static String rs(double v) {
        return Math.abs(v - Math.rint(v)) < 0.005 ? String.format(Locale.ENGLISH, "₹%,.0f", v)
                                                  : String.format(Locale.ENGLISH, "₹%,.2f", v);
    }
}
