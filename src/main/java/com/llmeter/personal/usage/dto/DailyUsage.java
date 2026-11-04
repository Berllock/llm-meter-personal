package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

public record DailyUsage(
        ProviderType provider,
        LocalDate date,
        String zone,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) String credits
) {
    public DailyUsage(final ProviderType provider, final LocalDate date, final String zone,
                      final Long inputTokens, final Long outputTokens, final Long cachedTokens) {
        this(provider, date, zone, inputTokens, outputTokens, cachedTokens, null);
    }
}
