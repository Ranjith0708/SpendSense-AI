package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.AnalysisResponse.TxnType;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionAccumulatorTest {

    private static Transaction txn(String details, String utr, double amount, TxnType type, ExpenseCategory category) {
        return new Transaction("2026-10-19", details, utr, amount, type, category, false);
    }

    @Test void duplicateUtrAcrossChunksIsKeptOnce() {
        TransactionAccumulator acc = new TransactionAccumulator();
        acc.add("chunk_1", List.of(txn("SWIGGY", "123456789012", 100, TxnType.DEBIT, ExpenseCategory.FOOD_AND_DINING)));
        acc.add("chunk_2", List.of(txn("SWIGGY", "123456789012", 100, TxnType.DEBIT, ExpenseCategory.FOOD_AND_DINING)));
        assertEquals(1, acc.result().size());
    }

    @Test void paymentsWithoutUtrAreNeverMerged() {
        TransactionAccumulator acc = new TransactionAccumulator();
        Transaction t = txn("Tea stall", "", 20, TxnType.DEBIT, ExpenseCategory.FOOD_AND_DINING);
        acc.add("chunk_1", List.of(t, t));
        assertEquals(2, acc.result().size());
    }

    @Test void invalidRowsAreDropped() {
        TransactionAccumulator acc = new TransactionAccumulator();
        acc.add("chunk_1", java.util.Arrays.asList(
                null,
                txn("zero", "", 0, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED),
                txn("negative", "", -5, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED),
                txn("nan", "", Double.NaN, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED),
                txn("inf", "", Double.POSITIVE_INFINITY, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED),
                txn("no type", "", 10, null, ExpenseCategory.OTHER_UNCATEGORIZED),
                txn("valid", "", 10, TxnType.DEBIT, ExpenseCategory.OTHER_UNCATEGORIZED)));
        List<Transaction> out = acc.result();
        assertEquals(1, out.size());
        assertEquals("valid", out.get(0).details());
    }

    @Test void missingCategoryDefaultsToUncategorized() {
        TransactionAccumulator acc = new TransactionAccumulator();
        acc.add("chunk_1", List.of(txn("Some Person", null, 50, TxnType.DEBIT, null)));
        Transaction t = acc.result().get(0);
        assertEquals(ExpenseCategory.OTHER_UNCATEGORIZED, t.category());
        assertEquals("", t.utr());
    }

    @Test void creditsAreMovedOutOfSpendingCategories() {
        TransactionAccumulator acc = new TransactionAccumulator();
        acc.add("chunk_1", List.of(txn("Razorpay", "", 500, TxnType.CREDIT, ExpenseCategory.UTILITIES_AND_BILLS)));
        assertTrue(acc.result().get(0).category() == ExpenseCategory.INCOME_AND_TRANSFERS);
    }
}
