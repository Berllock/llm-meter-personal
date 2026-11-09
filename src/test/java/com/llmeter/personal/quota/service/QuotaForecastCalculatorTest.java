package com.llmeter.personal.quota.service;

import com.llmeter.personal.quota.dto.QuotaWindow;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.dto.QuotaForecast.Status.*;

class QuotaForecastCalculatorTest {
    private final QuotaForecastCalculator calculator = new QuotaForecastCalculator();
    private final Instant start = Instant.parse("2026-11-09T12:00:00Z");
    private QuotaWindow sample(final int seconds, final double used) {
        return new QuotaWindow("primary", used, 300, start.plusSeconds(14400), "codex", start.plusSeconds(seconds));
    }
    // Validates percentage-point arithmetic and a projection anchored to the last observation, not collection time.
    @Test void shouldProjectAtRecentAveragePace() {
        final var result = calculator.calculate(List.of(sample(0, 20), sample(120, 30), sample(240, 40)), start.plusSeconds(270), true);
        assertThat(result.status()).isEqualTo(ESTIMATED);
        assertThat(result.percentagePointsPerHour()).isEqualTo(300);
        assertThat(result.projectedExhaustionAt()).isEqualTo(start.plusSeconds(960));
        assertThat(result.earliestExhaustionAt()).isEqualTo(result.projectedExhaustionAt());
        assertThat(result.latestExhaustionAt()).isEqualTo(result.projectedExhaustionAt());
    }
    // Validates fast/slow interval scenarios describe observed variability, with an open slow bound after flat intervals.
    @Test void shouldExposePaceVariationWithoutConfidenceClaims() {
        final var result = calculator.calculate(List.of(sample(0, 20), sample(120, 25), sample(240, 40)), start.plusSeconds(240), true);
        assertThat(result.earliestExhaustionAt()).isBefore(result.projectedExhaustionAt());
        assertThat(result.latestExhaustionAt()).isAfter(result.projectedExhaustionAt());
        final var paused = calculator.calculate(List.of(sample(0, 20), sample(120, 20), sample(240, 40)), start.plusSeconds(240), true);
        assertThat(paused.latestExhaustionAt()).isNull();
    }
    // Validates insufficient counts, short spans and unverified account/plan context never produce dates.
    @Test void shouldRequireComparableEvidence() {
        assertThat(calculator.calculate(List.of(sample(0, 20), sample(120, 30)), start.plusSeconds(120), true).status()).isEqualTo(INSUFFICIENT_DATA);
        assertThat(calculator.calculate(List.of(sample(0, 20), sample(10, 30), sample(20, 40)), start.plusSeconds(20), true).status()).isEqualTo(INSUFFICIENT_DATA);
        assertThat(calculator.calculate(List.of(sample(0, 20), sample(120, 30), sample(240, 40)), start.plusSeconds(240), false).status()).isEqualTo(UNVERIFIED_CONTEXT);
    }
    // Validates stale, future, expired and reported-exhausted evidence has explicit outcomes.
    @Test void shouldHandleObservationAgeAndReset() {
        final var samples = List.of(sample(0, 20), sample(120, 30), sample(240, 40));
        assertThat(calculator.calculate(samples, start.plusSeconds(541), true).status()).isEqualTo(STALE);
        assertThat(calculator.calculate(samples, start, true).status()).isEqualTo(STALE);
        assertThat(calculator.calculate(samples, start.plusSeconds(14400), true).status()).isEqualTo(RESET_PASSED);
        assertThat(calculator.calculate(List.of(sample(240, 100)), start.plusSeconds(240), true).status()).isEqualTo(EXHAUSTED);
    }
    // Validates resets, gaps, timestamp replays and cross-window samples cannot masquerade as a continuous pace.
    @Test void shouldRejectDiscontinuousCounters() {
        for (final var samples : List.of(
                List.of(sample(0, 20), sample(120, 10), sample(240, 40)),
                List.of(sample(0, 20), sample(900, 30), sample(1020, 40)),
                List.of(sample(0, 20), sample(0, 30), sample(240, 40)),
                List.of(sample(0, 20), sample(120, 30), new QuotaWindow("secondary", 40, 300, start.plusSeconds(14400), "codex", start.plusSeconds(240))))) {
            assertThat(calculator.calculate(samples, samples.getLast().observedAt(), true).status()).isEqualTo(DISCONTINUOUS);
        }
    }
    // Validates zero growth is unknown future usage, and a slow central pace reports reset first without a fictitious post-reset date.
    @Test void shouldHandleFlatAndResetFirstPaces() {
        assertThat(calculator.calculate(List.of(sample(0, 20), sample(120, 20), sample(240, 20)), start.plusSeconds(240), true).status()).isEqualTo(NO_GROWTH);
        final var slow = calculator.calculate(List.of(sample(0, 20), sample(120, 20.01), sample(240, 20.02)), start.plusSeconds(240), true);
        assertThat(slow.status()).isEqualTo(RESET_FIRST);
        assertThat(slow.projectedExhaustionAt()).isNull();
        assertThat(slow.percentagePointsPerHour()).isPositive();
    }
    // Validates observations outside their stated window do not generate projections even with recent timestamps.
    @Test void shouldRejectOutsideWindowEvidence() {
        final var invalid = new QuotaWindow("primary", 30, 1, start.plusSeconds(14400), "codex", start);
        assertThat(calculator.calculate(List.of(invalid), start, true).status()).isEqualTo(DISCONTINUOUS);
    }
}
