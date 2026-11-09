package com.llmeter.personal.quota.dto;

import com.llmeter.personal.provider.domain.ProviderType;

/** Availability is independent of usage-collection success. */
public record ProviderQuota(ProviderType provider, Status status, QuotaSnapshot snapshot) {
    public enum Status { OBSERVED, UNAVAILABLE, ERROR }
}
