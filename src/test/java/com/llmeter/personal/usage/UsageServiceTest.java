package com.llmeter.personal.usage;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import com.llmeter.personal.usage.domain.service.UsageService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageServiceTest {

    @Test
    void shouldReturnUsageFromAllAdapters() {
        final var collectedAt = Instant.parse("2026-10-22T12:00:00Z");
        final var codexUsage = new UsageSnapshot(
                ProviderType.CODEX, 100L, 40L, null, collectedAt
        );
        final var claudeUsage = new UsageSnapshot(
                ProviderType.CLAUDE, 200L, 60L, 30L, collectedAt
        );
        final List<LlmProviderAdapter> adapters = List.of(
                new FakeLlmProviderAdapter(codexUsage),
                new FakeLlmProviderAdapter(claudeUsage)
        );
        final var service = new UsageService(adapters);

        final var result = service.getUsage();

        assertEquals(List.of(codexUsage, claudeUsage), result);
    }

    @Test
    void shouldReturnEmptyListWhenThereAreNoAdapters() {
        final var service = new UsageService(List.of());

        final var result = service.getUsage();

        assertTrue(result.isEmpty());
    }
}
