package com.llmeter.personal.provider.codex.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class CodexConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "llm-meter.codex", name = "session-file")
    public LlmProviderAdapter codexAdapter(
            @Value("${llm-meter.codex.session-file}") final String sessionFilePath,
            @Value("${llm-meter.codex.sessions-directory:}") final String sessionsDirectory
    ) {
        validateSource(sessionFilePath, sessionsDirectory);
        return new CodexAdapter(Path.of(sessionFilePath));
    }

    @Bean
    @ConditionalOnProperty(prefix = "llm-meter.codex", name = "sessions-directory")
    public LlmProviderAdapter codexDirectoryAdapter(
            @Value("${llm-meter.codex.sessions-directory}") final String sessionsDirectory,
            @Value("${llm-meter.codex.session-file:}") final String sessionFilePath
    ) {
        validateSource(sessionsDirectory, sessionFilePath);
        return CodexAdapter.forDirectory(Path.of(sessionsDirectory));
    }

    private void validateSource(final String selectedPath, final String otherPath) {
        if (selectedPath.isBlank()) {
            throw new IllegalArgumentException("Codex session source path must not be blank");
        }
        if (!otherPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Configure only one of llm-meter.codex.session-file or llm-meter.codex.sessions-directory"
            );
        }
    }
}
