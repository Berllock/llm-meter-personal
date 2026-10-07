package com.llmeter.personal.usage.domain.dto;

import com.llmeter.personal.provider.domain.ProviderType;

import java.time.Instant;

public record UsageSnapshot(
        ProviderType provider,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        Instant collectedAt
) {
}
