package com.llmeter.personal.quota.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record QuotaSnapshot(
        ProviderType provider,
        Long used,
        Long limit,
        Instant resetAt,
        Instant collectedAt,
        Instant observedAt,
        String planType,
        String limitId,
        List<QuotaWindow> windows,
        @JsonInclude(JsonInclude.Include.NON_NULL) String source,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int excludedWindows
) {
    public QuotaSnapshot(final ProviderType provider, final Long used, final Long limit, final Instant resetAt,
                         final Instant collectedAt, final Instant observedAt, final String planType,
                         final String limitId, final List<QuotaWindow> windows) {
        this(provider, used, limit, resetAt, collectedAt, observedAt, planType, limitId, windows, null, 0);
    }
    public QuotaSnapshot(final ProviderType provider, final Long used, final Long limit,
                         final Instant resetAt, final Instant collectedAt) {
        this(provider, used, limit, resetAt, collectedAt, null, null, null, List.of());
    }
    public QuotaSnapshot {
        windows = List.copyOf(windows);
        if (excludedWindows < 0) throw new IllegalArgumentException("Invalid excluded quota window count");
    }
}
