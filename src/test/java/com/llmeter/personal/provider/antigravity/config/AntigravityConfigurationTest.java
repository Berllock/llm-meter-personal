package com.llmeter.personal.provider.antigravity.config;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AntigravityConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AntigravityConfiguration.class);

    // Validates an unconfigured provider stays disabled instead of inspecting arbitrary home directories.
    @Test
    void shouldRequireConfiguration() {
        runner.run((final var context) -> assertThat(context).doesNotHaveBean(LlmProviderAdapter.class));
    }

    // Validates the explicit brain directory enables the Antigravity adapter independently of Codex.
    @Test
    void shouldEnableConfiguredProvider(@TempDir final Path root) {
        runner.withPropertyValues("llm-meter.antigravity.brain-directory=" + root).run((final var context) -> {
            assertThat(context).hasNotFailed().hasSingleBean(LlmProviderAdapter.class);
            assertThat(context.getBean(LlmProviderAdapter.class).provider()).isEqualTo(ProviderType.ANTIGRAVITY);
        });
    }

    // Validates blank configuration fails startup rather than producing a misleading empty source.
    @Test
    void shouldRejectBlankSource() {
        runner.withPropertyValues("llm-meter.antigravity.brain-directory= ").run((final var context) -> assertThat(context).hasFailed());
    }
}
