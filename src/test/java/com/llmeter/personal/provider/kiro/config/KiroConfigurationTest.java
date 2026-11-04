package com.llmeter.personal.provider.kiro.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class KiroConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(KiroConfiguration.class);
    // Validates the backend requires explicit source configuration and rejects a blank source.
    @Test
    void shouldRequireAnExplicitNonblankSource() {
        runner.run((final var context) -> assertThat(context).doesNotHaveBean(LlmProviderAdapter.class));
        runner.withPropertyValues("llm-meter.kiro.sessions-directory= ").run((final var context) -> assertThat(context).hasFailed());
    }
    // Validates Kiro registration is independent of the other providers.
    @Test
    void shouldRegisterConfiguredKiro(@TempDir final Path root) {
        runner.withPropertyValues("llm-meter.kiro.sessions-directory=" + root).run((final var context) -> {
            assertThat(context).hasNotFailed().hasSingleBean(LlmProviderAdapter.class);
            assertThat(context.getBean(LlmProviderAdapter.class).provider()).isEqualTo(ProviderType.KIRO);
        });
    }
}
