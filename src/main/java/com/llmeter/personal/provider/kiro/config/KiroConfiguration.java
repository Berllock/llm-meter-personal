package com.llmeter.personal.provider.kiro.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.kiro.service.adapter.KiroAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;

@Configuration
public class KiroConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "llm-meter.kiro", name = "sessions-directory")
    public LlmProviderAdapter kiroAdapter(@Value("${llm-meter.kiro.sessions-directory}") final String directory) {
        if (directory.isBlank()) throw new IllegalArgumentException("Kiro session source path must not be blank");
        return new KiroAdapter(Path.of(directory));
    }
}
