package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaComparability;
import com.llmeter.personal.quota.dto.QuotaForecast;
import com.llmeter.personal.quota.dto.QuotaHistoryEntry;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import com.llmeter.personal.quota.service.QuotaForecastCalculator;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Adds archive evidence only to sparse native forecasts; the current source remains authoritative. */
public final class QuotaHistoryForecasts {
    private static final int MAX_SAMPLES = 256;
    private final QuotaComparabilityPolicy policy = new QuotaComparabilityPolicy();
    private final QuotaForecastCalculator calculator = new QuotaForecastCalculator();

    public QuotaSnapshot enrich(final QuotaSnapshot current, final String context,
                                final List<QuotaHistoryEntry> entries, final Instant now) {
        if (current.provider() != ProviderType.CODEX || current.accountEvidence() == null) return current;
        final var comparison = policy.evaluate(context, current, policy.accountFingerprint(current), false);
        final var timeline = entries.stream().filter((final var entry) -> entry.snapshot().provider() == current.provider())
                .sorted(Comparator.comparing((final QuotaHistoryEntry entry) -> entry.snapshot().observedAt()).reversed()
                        .thenComparing(Comparator.comparing(QuotaHistoryEntry::savedAt).reversed()).thenComparing(QuotaHistoryEntry::id)).toList();
        final var windows = comparison.groups().stream().map((final var group) -> {
            final var window = current.windows().get(group.index());
            final var nativeForecast = window.forecast();
            if (group.key() == null || nativeForecast != null && nativeForecast.status() != QuotaForecast.Status.INSUFFICIENT_DATA) return window;
            final var forecast = forecast(current, window, group.key(), timeline, now);
            if (forecast.status() != QuotaForecast.Status.DISCONTINUOUS
                    && forecast.sampleCount() <= (nativeForecast == null ? 1 : nativeForecast.sampleCount())) return window;
            return new QuotaWindow(window.name(), window.usedPercent(), window.windowMinutes(), window.resetAt(),
                    window.limitId(), window.observedAt(), forecast, window.displayName());
        }).toList();
        return new QuotaSnapshot(current.provider(), current.used(), current.limit(), current.resetAt(), current.collectedAt(),
                current.observedAt(), current.planType(), current.limitId(), windows, current.source(), current.excludedWindows(),
                current.creditQuota(), current.accountEvidence());
    }

    private QuotaForecast forecast(final QuotaSnapshot current, final QuotaWindow window, final String key,
                                   final List<QuotaHistoryEntry> entries, final Instant now) {
        final var samples = new TreeMap<Instant, QuotaWindow>();
        samples.put(window.observedAt(), window);
        final var cutoff = window.observedAt().minus(QuotaForecastCalculator.LOOKBACK);
        final var boundary = window.forecast() == null ? null : window.forecast().historyBoundary();
        for (final var entry : entries) {
            if (entry.snapshot().observedAt().isBefore(cutoff)) break;
            if (boundary != null && !entry.snapshot().observedAt().isAfter(boundary)) break;
            // A newer archived source event means the live source rolled back; never project from future evidence.
            if (entry.snapshot().observedAt().isAfter(current.observedAt())) return discontinuous(window);
            final var comparison = entry.comparability();
            if (!entry.accountVerified() || comparison == null
                    || comparison.accountStatus() != QuotaComparability.AccountStatus.RECORDED_ACCOUNT) break;
            final var match = comparison.groups().stream().filter((final var group) -> key.equals(group.key())
                    && group.unit() == QuotaComparability.Unit.PERCENTAGE).findFirst();
            // Do not skip an intervening unidentified, changed or missing window and reconnect an older cohort.
            if (match.isEmpty()) break;
            final var sample = entry.snapshot().windows().get(match.orElseThrow().index());
            if (sample.observedAt().isAfter(window.observedAt())) return discontinuous(window);
            if (boundary != null && !sample.observedAt().isAfter(boundary)) continue;
            if (sample.observedAt().isBefore(cutoff)) continue;
            final var previous = samples.putIfAbsent(sample.observedAt(), sample);
            if (previous != null && Double.compare(previous.usedPercent(), sample.usedPercent()) != 0) return discontinuous(window);
        }
        while (samples.size() > MAX_SAMPLES) samples.pollFirstEntry();
        return calculator.calculate(List.copyOf(samples.values()), now, true).withSource(QuotaForecast.Source.SAVED_HISTORY);
    }

    private QuotaForecast discontinuous(final QuotaWindow window) {
        return new QuotaForecast(QuotaForecast.Status.DISCONTINUOUS, 1, window.observedAt(), window.observedAt(),
                null, null, null, null, QuotaForecast.Source.SAVED_HISTORY);
    }
}
