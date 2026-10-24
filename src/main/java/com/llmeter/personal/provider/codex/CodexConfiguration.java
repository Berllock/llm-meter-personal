package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class CodexConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "llm-meter.codex",
            name = "session-file"
    )

    public LlmProviderAdapter codexAdapter(
            @Value("${llm-meter.codex.session-file}") final String sessionFilePath)
    {
        final var sessionFile = Path.of(sessionFilePath);
        return new CodexAdapter(sessionFile);

    }

}
