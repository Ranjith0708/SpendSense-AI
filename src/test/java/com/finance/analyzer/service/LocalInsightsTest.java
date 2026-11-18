package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.*;

import com.finance.analyzer.dto.AnalysisResponse.CategorySummary;
import com.finance.analyzer.dto.AnalysisResponse.Narrative;
import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.AnalysisResponse.TxnType;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalInsightsTest {

    @Test void buildsSummaryInsightsFeedbackAndTips() {
        List<Transaction> spend = new ArrayList<>();
        for (int i = 0; i < 12; i++) spend.add(new Transaction("d", "DHANUSHA V S", "", 15, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED, false));
        spend.add(new Transaction("d", "SWIGGY", "", 800, TxnType.DEBIT, ExpenseCategory.FOOD_AND_DINING, false));
        List<CategorySummary> breakdown = List.of(
                new CategorySummary(ExpenseCategory.FOOD_AND_DINING, "Food & Dining", "#fff", 1, 800, 81.6),
                new CategorySummary(ExpenseCategory.OTHER_UNCATEGORIZED, "Other / Uncategorized", "#fff", 12, 180, 18.4));
        Narrative n = LocalInsights.build(980, 0, breakdown, spend);
        assertTrue(n.summary().contains("₹980") && n.summary().contains("Food & Dining"));
        assertTrue(n.insights().stream().anyMatch(s -> s.contains("DHANUSHA V S 12 times")));
        assertTrue(n.feedback().stream().anyMatch(s -> s.contains("81.6%")));
        assertTrue(n.savingsTips().stream().anyMatch(s -> s.contains("₹80")));
    }

    @Test void emptySpendGivesAnExplicitMessage() {
        assertEquals("No spending was found in this statement.", LocalInsights.build(0, 0, List.of(), List.of()).summary());
    }

    @Test void fillGapsKeepsModelTextAndFillsBlanks() {
        Narrative fallback = new Narrative("fs", List.of("fi"), List.of("ff"), List.of("ft"));
        Narrative merged = LocalInsights.fillGaps(new Narrative("mine", List.of(), List.of("f"), null), fallback);
        assertEquals("mine", merged.summary());
        assertEquals(List.of("fi"), merged.insights());
        assertEquals(List.of("f"), merged.feedback());
        assertEquals(List.of("ft"), merged.savingsTips());
    }

    @Test void formatsRupees() {
        assertEquals("₹980", LocalInsights.rs(980));
        assertEquals("₹1,234.50", LocalInsights.rs(1234.5));
    }
}
