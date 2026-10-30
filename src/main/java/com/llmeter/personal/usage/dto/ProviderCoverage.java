package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import java.util.Objects;

/** Counts identified source sessions, never a percentage of token coverage or a period attribution. */
public record ProviderCoverage(ProviderType provider, int observedSessions, int pendingSessions) {
    public ProviderCoverage {
        Objects.requireNonNull(provider);
        if (observedSessions < 0 || pendingSessions < 0) {
            throw new IllegalArgumentException("Coverage counts must be nonnegative");
        }
    }
}
