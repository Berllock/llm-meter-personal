package com.llmeter.personal.usage.service;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.CollectionStatus;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.ProviderCoverage;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageCollectionServiceTest {
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-26"), ZoneId.of("UTC"));
    private LlmProviderAdapter adapter(final ProviderType provider, final int pending, final boolean fail) {
        return new FakeLlmProviderAdapter(new UsageSnapshot(provider, null, null, null, Instant.EPOCH)) {
            @Override public ProviderDailyUsage getDailyUsageReport(final UsagePeriod requested) {
                assertThat(requested).isEqualTo(period);
                if (fail) {
                    throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "PRIVATE_DETAIL");
                }
                return new ProviderDailyUsage(List.of(), new ProviderCoverage(provider, 0, pending));
            }
        };
    }
    // Validates all provider coverages participate in the overall source status without inventing observations.
    @Test void shouldCombineProviderCoverage() {
        final var result = new UsageService(List.of(adapter(ProviderType.CODEX, 1, false), adapter(ProviderType.CLAUDE, 0, false)))
                .collectDailyUsage(period);
        assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(result.coverage()).hasSize(2);
        assertThat(result.usage()).isEmpty();
        assertThat(result.from()).isEqualTo(period.from());
        assertThat(result.zone()).isEqualTo("UTC");
    }
    // Validates a hard provider failure prevents a misleading response from the remaining providers.
    @Test void shouldKeepHardFailuresBlocking() {
        final var service = new UsageService(List.of(adapter(ProviderType.CODEX, 1, false), adapter(ProviderType.CLAUDE, 0, true)));
        assertThrows(ProviderCollectionException.class, () -> service.collectDailyUsage(period));
    }
}
