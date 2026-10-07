package com.llmeter.personal.quota.domain.dto;

import com.llmeter.personal.provider.domain.ProviderType;

import java.time.Instant;

public record QuotaSnapshot(
        ProviderType provider,
        Long used,
        Long limit,
        Instant resetAt,
        Instant collectedAt
) {
}
