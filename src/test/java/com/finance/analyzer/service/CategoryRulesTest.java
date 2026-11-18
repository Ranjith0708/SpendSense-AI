package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.*;

import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.AnalysisResponse.TxnType;
import com.finance.analyzer.model.ExpenseCategory;
import org.junit.jupiter.api.Test;

class CategoryRulesTest {

    private static Transaction tx(String details, TxnType type, ExpenseCategory model) {
        return new Transaction("2026-10-19", details, "", 100, type, model, false);
    }

    private static ExpenseCategory cat(String details, ExpenseCategory model) {
        return CategoryRules.apply(tx(details, TxnType.DEBIT, model)).category();
    }

    @Test void creditsAreAlwaysIncome() {
        assertEquals(ExpenseCategory.INCOME_AND_TRANSFERS,
                CategoryRules.apply(tx("Razorpay", TxnType.CREDIT, ExpenseCategory.UTILITIES_AND_BILLS)).category());
    }

    @Test void knownMerchantsOverrideWrongModelAnswers() {
        assertEquals(ExpenseCategory.TRANSPORTATION_AND_FUEL, cat("Bangalore Metro Rail Corporation Ltd", ExpenseCategory.HEALTH_AND_MEDICAL));
        assertEquals(ExpenseCategory.FOOD_AND_DINING, cat("SWIGGY", ExpenseCategory.OTHER_UNCATEGORIZED));
        assertEquals(ExpenseCategory.FOOD_AND_DINING, cat("Royal bekary", null));
        assertEquals(ExpenseCategory.GROCERIES_AND_HOUSEHOLD, cat("Swapna Provision Store", null));
        assertEquals(ExpenseCategory.UTILITIES_AND_BILLS, cat("Paid - Mobile Recharge", null));
        assertEquals(ExpenseCategory.TRAVEL_AND_VACATIONS, cat("RAILTEL CORPORATION OF INDIA LIMITED", null));
        assertEquals(ExpenseCategory.SHOPPING_AND_PERSONAL_CARE, cat("MYNTRA DESIGNS PRIVATE LIMITED", null));
        assertEquals(ExpenseCategory.ENTERTAINMENT_AND_SUBSCRIPTIONS, cat("Bookmyshow", null));
        assertEquals(ExpenseCategory.HEALTH_AND_MEDICAL, cat("Mani Hospital", null));
    }

    @Test void personNamesKeepTheModelsCategory() {
        assertEquals(ExpenseCategory.OTHER_UNCATEGORIZED, cat("DHANUSHA V S", ExpenseCategory.OTHER_UNCATEGORIZED));
    }

    @Test void keywordsMatchWholeWordsOnly() {
        assertEquals(ExpenseCategory.OTHER_UNCATEGORIZED, cat("Ambulance Services", ExpenseCategory.OTHER_UNCATEGORIZED));
    }

    @Test void selfTransfersAreLeftAlone() {
        Transaction self = new Transaction("d", "Swiggy", "", 5, TxnType.DEBIT, ExpenseCategory.INCOME_AND_TRANSFERS, true);
        assertSame(self, CategoryRules.apply(self));
    }
}
