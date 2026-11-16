package com.llmeter.personal.quota.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** A recent-pace scenario, never a provider guarantee or a token allowance. */
public record QuotaForecast(Status status, int sampleCount, Instant sampleFrom, Instant sampleTo,
                            Double percentagePointsPerHour, Instant projectedExhaustionAt,
                            Instant earliestExhaustionAt, Instant latestExhaustionAt,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Source source,
                            @JsonIgnore Instant historyBoundary) {
    public enum Source { NATIVE_LOGS, SAVED_HISTORY }
    public QuotaForecast(final Status status, final int sampleCount, final Instant sampleFrom, final Instant sampleTo,
                         final Double percentagePointsPerHour, final Instant projectedExhaustionAt,
                         final Instant earliestExhaustionAt, final Instant latestExhaustionAt) {
        this(status, sampleCount, sampleFrom, sampleTo, percentagePointsPerHour, projectedExhaustionAt,
                earliestExhaustionAt, latestExhaustionAt, null, null);
    }
    public QuotaForecast(final Status status, final int sampleCount, final Instant sampleFrom, final Instant sampleTo,
                         final Double percentagePointsPerHour, final Instant projectedExhaustionAt,
                         final Instant earliestExhaustionAt, final Instant latestExhaustionAt, final Source source) {
        this(status, sampleCount, sampleFrom, sampleTo, percentagePointsPerHour, projectedExhaustionAt,
                earliestExhaustionAt, latestExhaustionAt, source, null);
    }
    public QuotaForecast withSource(final Source value) {
        return new QuotaForecast(status, sampleCount, sampleFrom, sampleTo, percentagePointsPerHour,
                projectedExhaustionAt, earliestExhaustionAt, latestExhaustionAt, value, historyBoundary);
    }
    public QuotaForecast withHistoryBoundary(final Instant value) {
        return new QuotaForecast(status, sampleCount, sampleFrom, sampleTo, percentagePointsPerHour,
                projectedExhaustionAt, earliestExhaustionAt, latestExhaustionAt, source, value);
    }
    public enum Status {
        INSUFFICIENT_DATA, UNVERIFIED_CONTEXT, STALE, RESET_PASSED, EXHAUSTED, DISCONTINUOUS, NO_GROWTH, RESET_FIRST, ESTIMATED
    }
}
