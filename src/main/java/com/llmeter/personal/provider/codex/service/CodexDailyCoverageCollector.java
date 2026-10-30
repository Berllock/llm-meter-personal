package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.dto.CodexDailyObservation;
import com.llmeter.personal.provider.codex.dto.CodexDailySessionUsage;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.ProviderCoverage;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CodexDailyCoverageCollector {
    public ProviderDailyUsage collect(final List<Path> files, final UsagePeriod period) {
        final var reader = new CodexSessionReader();
        return aggregateObservations(files.stream()
                .map((final var file) -> reader.readDailyObservation(file, period.zone())).toList(), period);
    }

    /** Shares reconciliation and aggregation with the local performance diagnostic. */
    public ProviderDailyUsage aggregateObservations(
            final List<CodexDailyObservation> observations, final UsagePeriod period
    ) {
        final Map<String, CodexDailyObservation> identities = new LinkedHashMap<>();
        final var observed = new ArrayList<CodexDailySessionUsage>();
        for (final var observation : observations) {
            final var previous = identities.putIfAbsent(observation.sessionId(), observation);
            if (previous != null && (!Objects.equals(previous.parentThreadId(), observation.parentThreadId())
                    || !Objects.equals(previous.forkedFromId(), observation.forkedFromId())
                    || (previous.dailyUsage() == null) != (observation.dailyUsage() == null))) {
                throw new ProviderCollectionException(ProviderCollectionFailure.CONFLICTING_DATA,
                        "Copies disagree on a session's identity or counter availability");
            }
            if (observation.dailyUsage() != null) {
                observed.add(observation.dailyUsage());
            }
        }
        final int pending = (int) identities.values().stream().filter((final var item) -> item.dailyUsage() == null).count();
        // Established aggregation still validates every observed copy, before filtering by period.
        final var usage = new CodexDailyUsageAggregator().aggregateSessions(observed, period);
        return new ProviderDailyUsage(usage,
                new ProviderCoverage(ProviderType.CODEX, identities.size() - pending, pending));
    }
}
