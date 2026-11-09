package com.llmeter.personal.quota.dto;

import com.llmeter.personal.provider.domain.ProviderType;

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
        List<QuotaWindow> windows
) {
    public QuotaSnapshot(final ProviderType provider, final Long used, final Long limit,
                         final Instant resetAt, final Instant collectedAt) {
        this(provider, used, limit, resetAt, collectedAt, null, null, null, List.of());
    }
    public QuotaSnapshot { windows = List.copyOf(windows); }
}
