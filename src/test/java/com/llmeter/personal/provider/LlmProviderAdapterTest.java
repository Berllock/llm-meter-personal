package com.llmeter.personal.provider;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LlmProviderAdapterTest {

    @Test
    void shouldRetrieveUsageThroughInterface() {
        final var expected = new UsageSnapshot(
                ProviderType.CODEX,
                100L,
                40L,
                null,
                Instant.parse("2026-10-22T12:00:00Z")
        );

        final LlmProviderAdapter adapter = new FakeLlmProviderAdapter(expected);

        final var result = adapter.getUsage();

        assertEquals(expected, result);
    }




}
