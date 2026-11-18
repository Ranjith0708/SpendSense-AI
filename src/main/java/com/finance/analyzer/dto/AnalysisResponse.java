package com.finance.analyzer.dto;

import com.finance.analyzer.model.ExpenseCategory;
import java.util.List;

/** Final API response. Transaction, TransactionBatch and Narrative are also the schemas the LLM must fill. */
public record AnalysisResponse(
        String summary,
        double totalExpense,
        double totalIncome,
        double netFlow,
        int uncategorizedCount,
        List<CategorySummary> categoryBreakdown,
        List<Transaction> transactions,
        List<String> insights,
        List<String> feedback,
        List<String> savingsTips,
        int totalChunks,
        int failedChunks,
        List<String> warnings) {

    public enum TxnType { DEBIT, CREDIT }

    public record Transaction(String date, String details, String utr, double amount,
                              TxnType type, ExpenseCategory category, boolean selfTransfer) {}

    public record CategorySummary(ExpenseCategory category, String displayLabel, String color,
                                  int transactionCount, double totalAmount, double percentage) {}

    /** LLM output 1: parsed and categorised transactions of one statement chunk. */
    public record TransactionBatch(List<Transaction> transactions) {}

    /** LLM output 2: coaching text generated from aggregated numbers. */
    public record Narrative(String summary, List<String> insights,
                            List<String> feedback, List<String> savingsTips) {}
}
