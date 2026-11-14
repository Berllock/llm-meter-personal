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
import com.llmeter.personal.usage.dto.ProviderUsageFailure;
import com.llmeter.personal.usage.dto.DailyUsage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

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
    // Validates failed sources have explicit errors instead of fabricated coverage or suppressing healthy sources.
    @Test void shouldIsolateProviderFailures() {
        final var service = new UsageService(List.of(adapter(ProviderType.CODEX, 1, false), adapter(ProviderType.CLAUDE, 0, true)));
        final var result = service.collectDailyUsage(period);
        assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(result.coverage()).containsExactly(new ProviderCoverage(ProviderType.CODEX, 0, 1));
        assertThat(result.failures()).containsExactly(new ProviderUsageFailure(ProviderType.CLAUDE, ProviderCollectionFailure.READ_FAILURE));
    }

    // Validates collection continues after the first failure and keeps token and credit observations separate.
    @Test void shouldPreserveHealthyObservationsAfterFailure() {
        final var codex = new DailyUsage(ProviderType.CODEX, period.from(), "UTC", 100L, 10L, null);
        final var kiro = new DailyUsage(ProviderType.KIRO, period.from(), "UTC", null, null, null, "0.3");
        final var service = new UsageService(List.of(adapter(ProviderType.ANTIGRAVITY, 0, true), observed(codex), observed(kiro)));
        final var result = service.collectDailyUsage(period);
        assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(result.usage()).containsExactly(codex, kiro);
        assertThat(result.coverage()).extracting(ProviderCoverage::provider).containsExactly(ProviderType.CODEX, ProviderType.KIRO);
        assertThat(result.failures()).extracting(ProviderUsageFailure::provider).containsExactly(ProviderType.ANTIGRAVITY);
    }

    // Validates an entirely failed selection is distinguishable from an empty successful source or a pending session.
    @Test void shouldReportFailedCollectionWhenAllSourcesFail() {
        final var result = new UsageService(List.of(adapter(ProviderType.CODEX, 0, true), adapter(ProviderType.KIRO, 0, true)))
                .collectDailyUsage(period);
        assertThat(result.status()).isEqualTo(CollectionStatus.FAILED);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.coverage()).isEmpty();
        assertThat(result.usage()).isEmpty();
        assertThat(result.modelUsage()).isEmpty();
    }

    // Validates provider selection occurs before I/O and a later successful refresh removes its previous failure.
    @Test void shouldFilterAndRecoverWithoutRetainingFailedData() {
        final var available = new boolean[]{false};
        final var source = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.CODEX, null, null, null, Instant.EPOCH)) {
            @Override public ProviderDailyUsage getDailyUsageReport(final UsagePeriod requested) {
                if (!available[0]) throw new IllegalStateException("PRIVATE_RUNTIME_DETAIL");
                return new ProviderDailyUsage(List.of(), new ProviderCoverage(ProviderType.CODEX, 0, 0));
            }
        };
        final var excluded = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.KIRO, null, null, null, Instant.EPOCH)) {
            @Override public ProviderDailyUsage getDailyUsageReport(final UsagePeriod requested) { throw new AssertionError("Unselected source read"); }
        };
        final var service = new UsageService(List.of(source, excluded));
        assertThat(service.collectDailyUsage(period, ProviderType.CODEX).failures()).containsExactly(
                new ProviderUsageFailure(ProviderType.CODEX, ProviderCollectionFailure.UNKNOWN));
        available[0] = true;
        final var recovered = service.collectDailyUsage(period, ProviderType.CODEX);
        assertThat(recovered.status()).isEqualTo(CollectionStatus.COMPLETE);
        assertThat(recovered.failures()).isEmpty();
        assertThat(service.collectDailyUsage(period, ProviderType.ANTIGRAVITY).status()).isEqualTo(CollectionStatus.COMPLETE);
    }

    private LlmProviderAdapter observed(final DailyUsage row) {
        return new FakeLlmProviderAdapter(new UsageSnapshot(row.provider(), null, null, null, Instant.EPOCH)) {
            @Override public ProviderDailyUsage getDailyUsageReport(final UsagePeriod requested) {
                return new ProviderDailyUsage(List.of(row), new ProviderCoverage(row.provider(), 1, 0, row.provider() != ProviderType.KIRO));
            }
        };
    }
}
