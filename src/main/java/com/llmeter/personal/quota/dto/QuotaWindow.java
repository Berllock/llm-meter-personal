package com.llmeter.personal.quota.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** A reported percentage, not an inferred absolute token allowance. */
public record QuotaWindow(String name, double usedPercent, Long windowMinutes, Instant resetAt,
                          String limitId, Instant observedAt,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          QuotaForecast forecast,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String displayName) {
    public QuotaWindow(final String name, final double usedPercent, final long windowMinutes, final Instant resetAt,
                       final String limitId, final Instant observedAt, final QuotaForecast forecast) {
        this(name, usedPercent, windowMinutes, resetAt, limitId, observedAt, forecast, null);
    }
    public QuotaWindow(final String name, final double usedPercent, final long windowMinutes, final Instant resetAt,
                       final String limitId, final Instant observedAt) {
        this(name, usedPercent, windowMinutes, resetAt, limitId, observedAt, null, null);
    }
    public QuotaWindow {
        if (!Double.isFinite(usedPercent) || usedPercent < 0 || usedPercent > 100 || (windowMinutes != null && windowMinutes < 1) || resetAt == null) {
            throw new IllegalArgumentException("Invalid reported quota window");
        }
    }
}
