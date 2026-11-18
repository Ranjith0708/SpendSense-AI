package com.finance.analyzer.service;

import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.AnalysisResponse.TxnType;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic category corrections applied after the model. Small local models regularly mislabel obvious merchants
 * (a Razorpay credit as "Utilities", a metro fare as "Health"), and a fixed keyword list is both faster and more
 * reliable for those. Anything not matched keeps the model's category.
 */
final class CategoryRules {

    private record Rule(ExpenseCategory category, Pattern pattern) {}

    private static Rule rule(ExpenseCategory c, String alternatives) {
        return new Rule(c, Pattern.compile("\\b(?:" + alternatives + ")\\b", Pattern.CASE_INSENSITIVE));
    }

    /** First match wins. */
    private static final List<Rule> RULES = List.of(
            rule(ExpenseCategory.TRANSPORTATION_AND_FUEL, "metro|bmrcl|bmtc|ksrtc|uber|rapido|namma yatri|fastag|petrol|iocl|hpcl|bpcl"),
            rule(ExpenseCategory.TRAVEL_AND_VACATIONS, "railtel|irctc|redbus|makemytrip|goibibo|indigo|airlines?|oyo"),
            rule(ExpenseCategory.FOOD_AND_DINING, "swiggy|zomato|cafe|froth|bekary|bakery|kitchen|fast foods?|samosa|juicy|restaurant|biryani|dhaba|tea|coffee"),
            rule(ExpenseCategory.GROCERIES_AND_HOUSEHOLD, "zepto|blinkit|bigbasket|instamart|dmart|provision|supermarket|kirana"),
            rule(ExpenseCategory.UTILITIES_AND_BILLS, "jio|myjio|airtel|vodafone|bsnl|recharges?|bescom|electricity|broadband|dth"),
            rule(ExpenseCategory.ENTERTAINMENT_AND_SUBSCRIPTIONS, "bookmyshow|netflix|hotstar|spotify|youtube|prime video|pvr|inox"),
            rule(ExpenseCategory.SHOPPING_AND_PERSONAL_CARE, "myntra|flipkart|amazon|nykaa|ajio|meesho"),
            rule(ExpenseCategory.HEALTH_AND_MEDICAL, "hospital|pharmacy|apollo|clinic|diagnostics?|medplus|pharmeasy|1mg"),
            rule(ExpenseCategory.EDUCATION_AND_LEARNING, "udemy|coursera|tuition|school|college|byju'?s"),
            rule(ExpenseCategory.INVESTMENTS_AND_SAVINGS, "zerodha|groww|mutual fund|nps|ppf"),
            rule(ExpenseCategory.DEBT_AND_EMIS, "emi|bajaj finance|loan|credit card"));

    private CategoryRules() {}

    /** Returns the transaction with any rule-based correction applied (the same instance if nothing changed). */
    static Transaction apply(Transaction t) {
        if (t.selfTransfer()) return t;
        if (t.type() == TxnType.CREDIT) {
            // Money coming in is never a spending category.
            return t.category() == ExpenseCategory.INCOME_AND_TRANSFERS ? t : withCategory(t, ExpenseCategory.INCOME_AND_TRANSFERS);
        }
        String d = t.details() == null ? "" : t.details().toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (r.pattern().matcher(d).find()) {
                return r.category() == t.category() ? t : withCategory(t, r.category());
            }
        }
        return t;
    }

    private static Transaction withCategory(Transaction t, ExpenseCategory c) {
        return new Transaction(t.date(), t.details(), t.utr(), t.amount(), t.type(), c, t.selfTransfer());
    }
}
