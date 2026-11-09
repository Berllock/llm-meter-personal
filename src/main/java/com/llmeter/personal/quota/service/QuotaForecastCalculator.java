package com.llmeter.personal.quota.service;

import com.llmeter.personal.quota.dto.QuotaForecast;
import com.llmeter.personal.quota.dto.QuotaWindow;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import static com.llmeter.personal.quota.dto.QuotaForecast.Status.*;

/** Pure calculation with an explicit clock; source/account compatibility belongs to the reader. */
public final class QuotaForecastCalculator {
    public static final Duration LOOKBACK = Duration.ofMinutes(30);
    private static final Duration MIN_SPAN = Duration.ofMinutes(2);
    private static final Duration MAX_GAP = Duration.ofMinutes(10);
    private static final Duration FRESHNESS = Duration.ofMinutes(5);

    public QuotaForecast calculate(final List<QuotaWindow> samples, final Instant now, final boolean verifiedContext) {
        if (samples.isEmpty()) return unavailable(INSUFFICIENT_DATA, samples);
        final var first = samples.getFirst();
        final var last = samples.getLast();
        if (!last.resetAt().isAfter(now)) return unavailable(RESET_PASSED, samples);
        if (last.observedAt() == null || last.observedAt().isAfter(now.plusSeconds(60))
                || last.observedAt().isBefore(now.minus(FRESHNESS))) return unavailable(STALE, samples);
        if (!verifiedContext) return unavailable(UNVERIFIED_CONTEXT, samples);
        if (!withinWindow(last)) return unavailable(DISCONTINUOUS, samples);
        if (last.usedPercent() >= 100) return unavailable(EXHAUSTED, samples);
        if (samples.size() < 3) return unavailable(INSUFFICIENT_DATA, samples);
        final double span = seconds(first.observedAt(), last.observedAt());
        if (span < MIN_SPAN.toSeconds() || span > LOOKBACK.toSeconds()) return unavailable(INSUFFICIENT_DATA, samples);
        double slowest = Double.POSITIVE_INFINITY;
        double fastest = 0;
        for (int index = 1; index < samples.size(); index++) {
            final var before = samples.get(index - 1);
            final var after = samples.get(index);
            final double elapsed = seconds(before.observedAt(), after.observedAt());
            if (!sameWindow(before, last) || !sameWindow(after, last) || !withinWindow(before) || !withinWindow(after)
                    || elapsed <= 0 || elapsed > MAX_GAP.toSeconds() || after.usedPercent() < before.usedPercent()) {
                return unavailable(DISCONTINUOUS, samples);
            }
            final double pace = (after.usedPercent() - before.usedPercent()) / elapsed;
            slowest = Math.min(slowest, pace);
            fastest = Math.max(fastest, pace);
        }
        final double pace = (last.usedPercent() - first.usedPercent()) / span;
        if (pace <= 0) return unavailable(NO_GROWTH, samples);
        final double remaining = 100 - last.usedPercent();
        final var projected = beforeReset(last, remaining / pace);
        return new QuotaForecast(projected == null ? RESET_FIRST : ESTIMATED, samples.size(), first.observedAt(),
                last.observedAt(), pace * 3600, projected, beforeReset(last, remaining / fastest),
                slowest > 0 ? beforeReset(last, remaining / slowest) : null);
    }

    private boolean sameWindow(final QuotaWindow first, final QuotaWindow second) {
        return first.name().equals(second.name()) && Objects.equals(first.limitId(), second.limitId())
                && first.windowMinutes() == second.windowMinutes() && first.resetAt().equals(second.resetAt());
    }
    private boolean withinWindow(final QuotaWindow sample) {
        try {
            return sample.observedAt() != null && sample.observedAt().isBefore(sample.resetAt())
                    && !sample.observedAt().isBefore(sample.resetAt().minus(Duration.ofMinutes(sample.windowMinutes())));
        } catch (final ArithmeticException | DateTimeException e) { return false; }
    }
    private double seconds(final Instant from, final Instant to) {
        final var duration = Duration.between(from, to);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
    private Instant beforeReset(final QuotaWindow sample, final double seconds) {
        final double untilReset = seconds(sample.observedAt(), sample.resetAt());
        if (!Double.isFinite(seconds) || seconds < 0 || seconds >= untilReset) return null;
        final var projected = sample.observedAt().plusMillis((long) Math.ceil(seconds * 1000));
        return projected.isBefore(sample.resetAt()) ? projected : null;
    }
    private QuotaForecast unavailable(final QuotaForecast.Status status, final List<QuotaWindow> samples) {
        return new QuotaForecast(status, samples.size(), samples.isEmpty() ? null : samples.getFirst().observedAt(),
                samples.isEmpty() ? null : samples.getLast().observedAt(), null, null, null, null);
    }
}
