package com.llmeter.personal.usage.dto;

import java.util.List;
import java.util.Objects;

public record ProviderDailyUsage(List<DailyUsage> usage, ProviderCoverage coverage) {
    public ProviderDailyUsage {
        usage = List.copyOf(usage);
        Objects.requireNonNull(coverage);
        if (usage.stream().anyMatch((final var row) -> row.provider() != coverage.provider())) {
            throw new IllegalArgumentException("Usage and coverage providers must match");
        }
    }
}
