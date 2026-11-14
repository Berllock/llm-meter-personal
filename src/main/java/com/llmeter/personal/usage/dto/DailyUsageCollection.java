package com.llmeter.personal.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.llmeter.personal.usage.domain.CollectionStatus;
import com.llmeter.personal.usage.domain.UsagePeriod;

import java.time.LocalDate;
import java.util.List;

public record DailyUsageCollection(
        LocalDate from, LocalDate to, String zone, CollectionStatus status,
        List<DailyUsage> usage, List<ProviderCoverage> coverage,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ModelDailyUsage> modelUsage,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ProviderUsageFailure> failures
) {
    public DailyUsageCollection(final LocalDate from, final LocalDate to, final String zone, final CollectionStatus status,
                                final List<DailyUsage> usage, final List<ProviderCoverage> coverage, final List<ModelDailyUsage> modelUsage) {
        this(from, to, zone, status, usage, coverage, modelUsage, List.of());
    }
    public DailyUsageCollection(final LocalDate from, final LocalDate to, final String zone, final CollectionStatus status,
                                final List<DailyUsage> usage, final List<ProviderCoverage> coverage) {
        this(from, to, zone, status, usage, coverage, List.of());
    }
    public DailyUsageCollection {
        usage = List.copyOf(usage);
        coverage = List.copyOf(coverage);
        modelUsage = List.copyOf(modelUsage);
        failures = List.copyOf(failures);
    }

    public static DailyUsageCollection of(final UsagePeriod period, final List<ProviderDailyUsage> reports) {
        return of(period, reports, List.of());
    }

    public static DailyUsageCollection of(final UsagePeriod period, final List<ProviderDailyUsage> reports,
                                          final List<ProviderUsageFailure> failures) {
        final var coverage = reports.stream().map(ProviderDailyUsage::coverage).toList();
        final var status = !failures.isEmpty() && reports.isEmpty() ? CollectionStatus.FAILED
                : !failures.isEmpty() || coverage.stream().anyMatch((final var item) -> item.pendingSessions() > 0)
                ? CollectionStatus.PARTIAL : CollectionStatus.COMPLETE;
        return new DailyUsageCollection(period.from(), period.to(), period.zone().getId(), status,
                reports.stream().flatMap((final var report) -> report.usage().stream()).toList(), coverage,
                reports.stream().flatMap((final var report) -> report.modelUsage().stream()).toList(), failures);
    }
}
