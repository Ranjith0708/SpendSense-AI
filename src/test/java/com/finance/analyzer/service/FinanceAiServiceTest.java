package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.*;

import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.dto.AnalysisResponse;
import com.finance.analyzer.dto.ProgressEvent;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.exception.AiProcessingException;
import com.finance.analyzer.llm.LlmClient;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the whole pipeline against a scripted model: no network, no Spring. */
class FinanceAiServiceTest {

    private static final AnalyzerProperties PROPS = new AnalyzerProperties(
            new AnalyzerProperties.Chunking(2, 8000), new AnalyzerProperties.Processing(1, 1, 0, 1),
            new AnalyzerProperties.Logging(false));

    /** Answers chunk prompts with one SWIGGY debit per statement line, and the commentary prompt with {@code narrative}. */
    private static FinanceAiService service(String narrative) {
        LlmClient client = new LlmClient() {
            public String model() { return "scripted"; }
            public String completeJson(String system, String user) {
                if (system.contains("personal-finance coach")) return narrative;
                if (user.contains("FAIL")) throw new AiProcessingException("OpenAI request failed (model 'gpt-4o-mini'): no reply within 600s", null);
                StringBuilder sb = new StringBuilder("{\"transactions\":[");
                int n = 0;
                for (String line : user.split("\n")) {
                    if (!line.startsWith("2026-")) continue;
                    if (n++ > 0) sb.append(',');
                    sb.append("{\"date\":\"2026-10-19\",\"details\":\"SWIGGY\",\"utr\":\"")
                      .append(String.format("%012d", 1000 + line.hashCode() % 1000 + n))
                      .append("\",\"amount\":100.0,\"type\":\"DEBIT\",\"category\":\"OTHER_UNCATEGORIZED\",\"selfTransfer\":false}");
                }
                return sb.append("]}").toString();
            }
        };
        StructuredLlmService llm = new StructuredLlmService(client, PROPS);
        return new FinanceAiService(new ChunkProcessor(llm, PROPS), llm, PROPS);
    }

    private static List<StatementChunk> chunks() {
        return List.of(new StatementChunk("chunk_1", 1, 2, "2026-10-19 a\n2026-10-20 b\n"),
                       new StatementChunk("chunk_2", 2, 1, "2026-10-21 c\n"));
    }

    @Test void percentagesStepPerChunkAndRulesFixCategories() {
        List<ProgressEvent> events = new ArrayList<>();
        AnalysisResponse r = service("{\"summary\":\"ok\",\"insights\":[\"i\"],\"feedback\":[\"f\"],\"savingsTips\":[\"t\"]}")
                .analyze("t1", chunks(), events::add);
        assertEquals(List.of(50, 100), events.stream().filter(e -> e.stage() == ProgressEvent.Stage.CHUNK_COMPLETED).map(ProgressEvent::percent).toList());
        assertEquals(3, r.transactions().size());
        assertEquals(ExpenseCategory.FOOD_AND_DINING, r.categoryBreakdown().get(0).category());
        assertEquals("ok", r.summary());
        assertTrue(r.warnings().isEmpty());
    }

    @Test void oddNarrativeShapesAreAccepted() {
        AnalysisResponse r = service("{\"summary\":[\"One.\",\"Two.\"],\"insights\":[{\"title\":\"Food\",\"detail\":\"Lots\"}],"
                + "\"feedback\":\"- cut food\\n- track spend\",\"tips\":[\"save\"]}").analyze("t2", chunks(), null);
        assertEquals("One. Two.", r.summary());
        assertEquals(List.of("cut food", "track spend"), r.feedback());
        assertEquals(List.of("save"), r.savingsTips());
        assertTrue(r.warnings().isEmpty());
    }

    @Test void unusableNarrativeFallsBackToLocalInsightsWithAWarning() {
        AnalysisResponse r = service("not json at all").analyze("t3", chunks(), null);
        assertFalse(r.summary().isBlank());
        assertFalse(r.insights().isEmpty());
        assertFalse(r.savingsTips().isEmpty());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("generated automatically"));
        assertEquals(300.0, r.totalExpense());
    }

    @Test void aFailedChunkGivesOnePlainWarningWithNoBackendDetails() {
        List<StatementChunk> chunks = List.of(new StatementChunk("chunk_1", 1, 2, "2026-10-19 a\n2026-10-20 b\n"),
                                              new StatementChunk("chunk_2", 2, 1, "2026-10-21 FAIL\n"));
        AnalysisResponse r = service("{\"summary\":\"ok\",\"insights\":[\"i\"],\"feedback\":[\"f\"],\"savingsTips\":[\"t\"]}")
                .analyze("t4", chunks, null);
        assertEquals(2, r.transactions().size());
        assertEquals(1, r.failedChunks());
        assertEquals(1, r.warnings().size());
        String w = r.warnings().get(0);
        assertTrue(w.contains("could not be analyzed"), w);
        assertFalse(w.toLowerCase().contains("chunk") || w.contains("OpenAI") || w.contains("600") || w.contains("gpt"), w);
    }

    @Test void whenEverythingFailsTheMessageIsGeneric() {
        List<StatementChunk> chunks = List.of(new StatementChunk("chunk_1", 1, 1, "2026-10-19 FAIL\n"));
        AiProcessingException e = assertThrows(AiProcessingException.class, () -> service("{}").analyze("t5", chunks, null));
        assertEquals(FinanceAiService.GENERIC_FAILURE, e.getMessage());
    }

    @Test void summaryFallbackWarningIsPlainLanguage() {
        AnalysisResponse r = service("not json").analyze("t6", chunks(), null);
        assertEquals(1, r.warnings().size());
        assertFalse(r.warnings().get(0).contains("OpenAI") || r.warnings().get(0).contains("attempt"));
    }
}
