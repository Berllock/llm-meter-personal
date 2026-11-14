package com.llmeter.personal.quota.config;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.service.history.QuotaHistoryCodec;
import com.llmeter.personal.quota.service.history.QuotaHistoryService;
import com.llmeter.personal.quota.service.history.QuotaHistoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;

@Configuration
public class QuotaHistoryConfiguration {
    @Bean
    public QuotaHistoryService quotaHistoryService(final Environment environment) {
        final String directory = environment.getProperty("llm-meter.quota.history-directory", "");
        final var contexts = new EnumMap<ProviderType, String>(ProviderType.class);
        final String codexDirectory = environment.getProperty("llm-meter.codex.sessions-directory", "");
        final String codexFile = environment.getProperty("llm-meter.codex.session-file", "");
        if (!codexDirectory.isBlank()) contexts.put(ProviderType.CODEX, context("codex-directory", codexDirectory, ""));
        else if (!codexFile.isBlank()) contexts.put(ProviderType.CODEX, context("codex-file", codexFile, ""));
        final String brain = environment.getProperty("llm-meter.antigravity.brain-directory", "");
        if (!brain.isBlank()) contexts.put(ProviderType.ANTIGRAVITY, context("antigravity", brain,
                environment.getProperty("llm-meter.antigravity.ide-hub-pid", "0")));
        final String kiro = environment.getProperty("llm-meter.kiro.sessions-directory", "");
        if (!kiro.isBlank()) contexts.put(ProviderType.KIRO, context("kiro", kiro,
                Path.of(environment.getProperty("llm-meter.kiro.cli-executable", System.getProperty("user.home") + "/.local/bin/kiro-cli")).toAbsolutePath().normalize().toString()));
        return new QuotaHistoryService(new QuotaHistoryStore(directory.isBlank() ? null : Path.of(directory)), contexts, Clock.systemUTC());
    }
    private String context(final String kind, final String source, final String selection) {
        // The hash scopes folders/configuration, never authenticates the account that produced an observation.
        final var normalized = Path.of(source).toAbsolutePath().normalize().toString();
        return QuotaHistoryCodec.hash(kind + "\n" + normalized.length() + ":" + normalized + "\n" + selection.length() + ":" + selection);
    }
}
