package com.finance.analyzer.service;

import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Merges partial chunk outputs into one transaction list. Call {@link #add} in chunk order so the final list keeps
 * the statement's order. Drops invalid rows and de-duplicates on 12-digit UTR (overlap between chunks or repeated
 * model output); payments without a UTR are kept because identical payments may be genuinely separate.
 * Not thread-safe: use from a single thread.
 */
@Slf4j
final class TransactionAccumulator {

    private final Map<String, Transaction> unique = new LinkedHashMap<>();
    private int anonymous;

    void add(String chunkId, List<Transaction> incoming) {
        int accepted = 0, duplicates = 0, invalid = 0, corrected = 0;
        for (Transaction raw : incoming) {
            if (raw == null || raw.amount() <= 0 || raw.type() == null) { invalid++; continue; }
            Transaction t = new Transaction(raw.date(), raw.details(), raw.utr() == null ? "" : raw.utr().trim(),
                    raw.amount(), raw.type(),
                    raw.category() == null ? ExpenseCategory.OTHER_UNCATEGORIZED : raw.category(), raw.selfTransfer());
            Transaction fixed = CategoryRules.apply(t);
            if (fixed != t) {
                corrected++;
                log.debug("category_rule chunkId={} payee={} {} -> {}", chunkId, t.details(), t.category(), fixed.category());
            }
            t = fixed;
            String key = t.utr().matches("\\d{12}") ? t.utr() : "row-" + (anonymous++);
            if (unique.putIfAbsent(key, t) == null) accepted++; else duplicates++;
        }
        log.info("chunk_accumulated chunkId={} received={} accepted={} duplicates={} invalid={} categoryCorrected={} runningTotal={}",
                chunkId, incoming.size(), accepted, duplicates, invalid, corrected, unique.size());
    }

    List<Transaction> result() { return new ArrayList<>(unique.values()); }
}
