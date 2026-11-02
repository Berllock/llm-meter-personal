package com.llmeter.personal.provider.antigravity.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.antigravity.service.adapter.AntigravityAdapter;
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
            @Value("${llm-meter.antigravity.brain-directory}") final String directory) {
        if (directory.isBlank()) throw new IllegalArgumentException("Antigravity brain source path must not be blank");
        return new AntigravityAdapter(Path.of(directory));
    }
}
