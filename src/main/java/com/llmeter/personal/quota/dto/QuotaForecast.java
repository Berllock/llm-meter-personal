package com.llmeter.personal.quota.dto;

import java.time.Instant;

/** A recent-pace scenario, never a provider guarantee or a token allowance. */
public record QuotaForecast(Status status, int sampleCount, Instant sampleFrom, Instant sampleTo,
                            Double percentagePointsPerHour, Instant projectedExhaustionAt,
                            Instant earliestExhaustionAt, Instant latestExhaustionAt) {
    public enum Status {
        INSUFFICIENT_DATA, UNVERIFIED_CONTEXT, STALE, RESET_PASSED, EXHAUSTED, DISCONTINUOUS, NO_GROWTH, RESET_FIRST, ESTIMATED
    }
}
