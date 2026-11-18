package com.finance.analyzer.llm;

import com.finance.analyzer.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Picks the {@link LlmClient} matching {@code llm.provider}. Misconfiguration (unknown provider, missing OpenAI key,
 * missing model or URL) fails at startup rather than on the first user request.
 */
@Component
@Slf4j
public class LlmClientFactory {

    private final Map<LlmProvider, LlmClient> clients = new EnumMap<>(LlmProvider.class);
    private final LlmProperties props;
    private LlmClient active;

    public LlmClientFactory(List<LlmClient> implementations, LlmProperties props) {
        implementations.forEach(c -> clients.put(c.provider(), c));
        this.props = props;
    }

    @PostConstruct
    public void init() {
        LlmProvider provider = LlmProvider.from(props.provider());
        LlmProperties.Endpoint cfg = provider == LlmProvider.OPENAI ? props.openai() : props.local();
        if (!StringUtils.hasText(cfg.baseUrl())) throw new IllegalStateException("llm." + key(provider) + ".base-url is not set");
        if (!StringUtils.hasText(cfg.model())) throw new IllegalStateException("llm." + key(provider) + ".model is not set");
        if (provider == LlmProvider.OPENAI && !StringUtils.hasText(cfg.apiKey())) {
            throw new IllegalStateException("llm.provider=openai requires an API key: set llm.openai.api-key or the OPENAI_API_KEY environment variable");
        }
        active = clients.get(provider);
        if (active == null) throw new IllegalStateException("No LlmClient registered for provider " + provider);
        log.info("llm_provider_selected provider={} model={} baseUrl={}", provider, cfg.model(), cfg.baseUrl());
    }

    /** The client for the configured provider. */
    public LlmClient active() { return active; }

    private static String key(LlmProvider p) { return p == LlmProvider.OPENAI ? "openai" : "local"; }
}
