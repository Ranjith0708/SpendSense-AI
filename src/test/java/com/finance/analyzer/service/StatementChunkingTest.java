package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.*;

import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.dto.StatementChunk;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementChunkingTest {

    private static StatementParserService parser(int perChunk) {
        return new StatementParserService(new AnalyzerProperties(
                new AnalyzerProperties.Chunking(perChunk, 8000), new AnalyzerProperties.Processing(1, 3, 0, 2),
                new AnalyzerProperties.Logging(false)));
    }

    @Test void splitsIntoSequentiallyNumberedChunks() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 7; i++) lines.add("2024-01-0" + i + " Paid to SHOP" + i + " DEBIT 10");
        List<StatementChunk> chunks = parser(3).chunk(lines);
        assertEquals(3, chunks.size());
        assertEquals("chunk_1", chunks.get(0).id());
        assertEquals("chunk_3", chunks.get(2).id());
        assertEquals(List.of(3, 3, 1), chunks.stream().map(StatementChunk::recordCount).toList());
    }

    @Test void mergesMultiLinePdfTransactionsIntoOneRecord() {
        List<StatementChunk> chunks = parser(25).chunk(List.of(
                "Jan 05, 2024 Paid to X DEBIT 100", "10:32 AM Transaction ID T1",
                "Jan 06, 2024 Paid to Y DEBIT 50", "10:40 AM Transaction ID T2"));
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).recordCount());
    }

    @Test void rowFormatsWithoutLeadingDatesGetOneRecordPerLine() {
        List<StatementChunk> chunks = parser(2).chunk(List.of("a=1 | b=2", "a=3 | b=4", "a=5 | b=6"));
        assertEquals(2, chunks.size());
        assertEquals(2, chunks.get(0).recordCount());
    }

    @Test void emptyInputGivesNoChunks() {
        assertTrue(parser(5).chunk(List.of()).isEmpty());
    }
}
