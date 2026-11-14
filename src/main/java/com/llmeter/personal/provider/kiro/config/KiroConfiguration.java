package com.llmeter.personal.provider.kiro.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.kiro.service.adapter.KiroAdapter;
import com.llmeter.personal.provider.kiro.service.KiroAccountQuotaService;
import com.llmeter.personal.provider.kiro.service.reader.KiroAccountClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
public class KiroConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "llm-meter.kiro", name = "sessions-directory")
    public LlmProviderAdapter kiroAdapter(@Value("${llm-meter.kiro.sessions-directory}") final String directory,
            @Value("${llm-meter.kiro.account-quota-enabled:false}") final boolean enabled,
            @Value("${llm-meter.kiro.cli-executable:${user.home}/.local/bin/kiro-cli}") final String executable) {
        if (directory.isBlank()) throw new IllegalArgumentException("Kiro session source path must not be blank");
        if (!enabled) return new KiroAdapter(Path.of(directory));
        final var client = new KiroAccountClient(Path.of(executable));
        final var quota = new KiroAccountQuotaService(client::read, Clock.systemUTC());
        return new KiroAdapter(Path.of(directory), quota::getQuota);
    }
}
