package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderType;

import java.time.LocalDate;

public record DailyUsage(
        ProviderType provider,
        LocalDate date,
        String zone,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens
) {
}
