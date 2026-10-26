package com.llmeter.personal.usage.service;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageServiceTest {

    // Verifies that snapshots from every configured adapter are preserved.
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

    // Verifies that no configured adapters produce an empty snapshot list.
    @Test
    void shouldReturnEmptyListWhenThereAreNoAdapters() {
        final var service = new UsageService(List.of());

        final var result = service.getUsage();

        assertTrue(result.isEmpty());
    }

    // Verifies daily query delegation to every provider, preserving unknown metrics and the requested period.
    @Test
    void shouldReturnDailyUsageFromAllAdapters() {
        final var date = LocalDate.parse("2026-10-25");
        final var period = new UsagePeriod(date, date, ZoneId.of("UTC"));
        final var codex = new DailyUsage(ProviderType.CODEX, date, "UTC", 100L, 10L, null);
        final var claude = new DailyUsage(ProviderType.CLAUDE, date, "UTC", 200L, 20L, 50L);
        final List<LlmProviderAdapter> adapters = List.of(dailyAdapter(codex, period), dailyAdapter(claude, period));

        assertEquals(List.of(codex, claude), new UsageService(adapters).getDailyUsage(period));
        assertTrue(new UsageService(List.of()).getDailyUsage(period).isEmpty());
    }

    // Verifies that a provider without daily collection fails explicitly instead of silently disappearing.
    @Test
    void shouldRejectUnsupportedDailyProvider() {
        final var adapter = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.CLAUDE, 1L, 1L, 0L, Instant.EPOCH));
        final var date = LocalDate.parse("2026-10-25");
        final var period = new UsagePeriod(date, date, ZoneId.of("UTC"));

        assertThrows(ProviderCollectionException.class, () -> new UsageService(List.of(adapter)).getDailyUsage(period));
    }

    private LlmProviderAdapter dailyAdapter(final DailyUsage daily, final UsagePeriod expectedPeriod) {
        return new FakeLlmProviderAdapter(new UsageSnapshot(daily.provider(), 0L, 0L, 0L, Instant.EPOCH)) {
            @Override
            public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
                assertEquals(expectedPeriod, period);
                return List.of(daily);
            }
        };
    }
}
