package com.finance.analyzer.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.finance.analyzer.config.LlmProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {

    private static LlmClient stub(LlmProvider p) {
        return new LlmClient() {
            public LlmProvider provider() { return p; }
            public String model() { return "stub"; }
            public String completeJson(String s, String u) { return "{}"; }
        };
    }

    private static LlmProperties props(String provider, String openAiKey) {
        return new LlmProperties(provider, 30,
                new LlmProperties.Endpoint("http://localhost:11434", "", "codellama", 0.1, 8192, 4096),
                new LlmProperties.Endpoint("https://api.openai.com/v1", openAiKey, "gpt-4o-mini", 0.1, null, null));
    }

    private static LlmClientFactory factory(LlmProperties p) {
        LlmClientFactory f = new LlmClientFactory(List.of(stub(LlmProvider.LOCAL), stub(LlmProvider.OPENAI)), p);
        f.init();
        return f;
    }

    @Test void localProviderNeedsNoApiKey() {
        assertEquals(LlmProvider.LOCAL, factory(props("local", "")).active().provider());
    }

    @Test void openAiProviderIsSelectedWithKeyAndIgnoresCase() {
        assertEquals(LlmProvider.OPENAI, factory(props("openai", "sk-test")).active().provider());
        assertEquals(LlmProvider.OPENAI, factory(props(" OpenAI ", "sk-test")).active().provider());
    }

    @Test void retiredAliasesAreRejected() {
        assertThrows(IllegalStateException.class, () -> factory(props("dev", "sk-test")));
    }

    @Test void openAiWithoutKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> factory(props("openai", "")));
    }

    @Test void unknownProviderFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> factory(props("banana", "x")));
    }
}
