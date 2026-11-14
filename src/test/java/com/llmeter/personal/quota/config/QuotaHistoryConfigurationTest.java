package com.llmeter.personal.quota.config;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaHistoryResponse;
import com.llmeter.personal.quota.service.history.QuotaHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaHistoryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(QuotaHistoryConfiguration.class);
    // Validates normal Spring tests/development never write to a user's home without explicit archive configuration.
    @Test void shouldDisableHistoryByDefault() {
        runner.run(context -> assertThat(context.getBean(QuotaHistoryService.class).read(null, 20).status()).isEqualTo(QuotaHistoryResponse.Status.DISABLED));
        runner.withPropertyValues("llm-meter.quota.history-directory=relative-folder").run(context -> assertThat(context).hasFailed());
    }
    // Validates an identical configured source restores its archive, while switching folders cannot reuse its observations.
    @Test void shouldSeparateConfiguredSourceFolders(@TempDir final Path root) {
        final var configured = runner.withPropertyValues("llm-meter.quota.history-directory=" + root.resolve("history"),
                "llm-meter.codex.sessions-directory=" + root.resolve("source-a"));
        configured.run(context -> context.getBean(QuotaHistoryService.class).record(codex(NOW, 20)));
        configured.run(context -> assertThat(context.getBean(QuotaHistoryService.class).read(ProviderType.CODEX, 20).observations()).hasSize(1));
        runner.withPropertyValues("llm-meter.quota.history-directory=" + root.resolve("history"),
                "llm-meter.codex.sessions-directory=" + root.resolve("source-b")).run(context ->
                assertThat(context.getBean(QuotaHistoryService.class).read(ProviderType.CODEX, 20).observations()).isEmpty());
    }
}
