package com.llmeter.personal.provider.antigravity.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.antigravity.service.adapter.AntigravityAdapter;
import com.llmeter.personal.provider.antigravity.service.AntigravityIdeQuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;

@Configuration
public class AntigravityConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "llm-meter.antigravity", name = "brain-directory")
    public LlmProviderAdapter antigravityAdapter(
            @Value("${llm-meter.antigravity.brain-directory}") final String directory,
            @Value("${llm-meter.antigravity.ide-quota-enabled:false}") final boolean ideQuotaEnabled,
            @Value("${llm-meter.antigravity.ide-hub-pid:0}") final long selectedPid,
            @Value("${llm-meter.antigravity.ide-shared-account:false}") final boolean sharedAccount) {
        if (directory.isBlank()) throw new IllegalArgumentException("Antigravity brain source path must not be blank");
        if (!ideQuotaEnabled) return new AntigravityAdapter(Path.of(directory));
        final var quota = new AntigravityIdeQuotaService(selectedPid, sharedAccount);
        return new AntigravityAdapter(Path.of(directory), quota::getQuota);
    }
}
