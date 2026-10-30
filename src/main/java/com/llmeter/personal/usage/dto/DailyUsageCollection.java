package com.llmeter.personal.usage.dto;

import com.llmeter.personal.usage.domain.CollectionStatus;
import com.llmeter.personal.usage.domain.UsagePeriod;
import java.time.LocalDate;
import java.util.List;

public record DailyUsageCollection(
        LocalDate from, LocalDate to, String zone, CollectionStatus status,
        List<DailyUsage> usage, List<ProviderCoverage> coverage
) {
    public DailyUsageCollection {
        usage = List.copyOf(usage);
        coverage = List.copyOf(coverage);
    }

    public static DailyUsageCollection of(final UsagePeriod period, final List<ProviderDailyUsage> reports) {
        final var coverage = reports.stream().map(ProviderDailyUsage::coverage).toList();
        final var status = coverage.stream().anyMatch((final var item) -> item.pendingSessions() > 0)
                ? CollectionStatus.PARTIAL : CollectionStatus.COMPLETE;
        return new DailyUsageCollection(period.from(), period.to(), period.zone().getId(), status,
                reports.stream().flatMap((final var report) -> report.usage().stream()).toList(), coverage);
    }
}
