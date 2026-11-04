package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record UsageSnapshot(
        ProviderType provider,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        Instant collectedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String credits
) {
    public UsageSnapshot(final ProviderType provider, final Long inputTokens, final Long outputTokens,
                         final Long cachedTokens, final Instant collectedAt) {
        this(provider, inputTokens, outputTokens, cachedTokens, collectedAt, null);
    }
}
