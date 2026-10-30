package com.llmeter.personal.desktop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopRuntimeConfigurationTest {

    // Verifies browser and IDE runs preserve their existing API behavior without desktop startup properties.
    @Test
    void shouldRemainDisabledForNormalRuns() {
        new ApplicationContextRunner().withUserConfiguration(DesktopRuntimeConfiguration.class).run((final var context) -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("desktopTokenFilter").doesNotHaveBean("desktopReadiness");
        });
    }

    // Verifies managed desktop startup requires a credential instead of exposing an unprotected owned API.
    @Test
    void shouldRejectDesktopModeWithoutCredential(@TempDir final Path directory) {
        new ApplicationContextRunner().withUserConfiguration(DesktopRuntimeConfiguration.class)
                .withPropertyValues("llm-meter.desktop.ready-file=" + directory.resolve("ready.json"),
                        "llm-meter.desktop.instance-id=synthetic-instance", "LLM_METER_DESKTOP_TOKEN=")
                .run((final var context) -> assertThat(context).hasFailed());
    }
}
