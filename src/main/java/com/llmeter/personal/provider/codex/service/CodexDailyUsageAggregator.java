package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;
import com.llmeter.personal.provider.codex.dto.CodexDailySessionUsage;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CodexDailyUsageAggregator {

    public List<DailyUsage> aggregate(final List<Path> files, final UsagePeriod period) {
        final var reader = new CodexSessionReader();
        return aggregateSessions(files.stream()
                .map((final var file) -> reader.readDaily(file, period.zone())).toList(), period);
    }

    public List<DailyUsage> aggregateSessions(final List<CodexDailySessionUsage> dailySessions, final UsagePeriod period) {
        final var sessions = new ArrayList<CodexSessionUsage>();
        final Map<String, Map<LocalDate, CodexTokenCounts>> uniqueDays = new LinkedHashMap<>();
        for (final var daily : dailySessions) {
            final var session = daily.session();
            sessions.add(session);
            final var days = daily.days();
            final var previous = uniqueDays.putIfAbsent(session.sessionId(), days);
            if (previous != null && !previous.equals(days)) {
                throw new ProviderCollectionException(
                        ProviderCollectionFailure.CONFLICTING_DATA, "Conflicting daily history for the same Codex session"
                );
            }
        }
        // Keep the established identity, lineage, attribution, conflict, and overflow guarantees.
        new CodexSessionAggregator().aggregate(sessions);

        final Map<LocalDate, CodexTokenCounts> totals = new TreeMap<>();
        for (final var days : uniqueDays.values()) {
            days.forEach((final var date, final var counts) -> {
                if (period.contains(date)) {
                    totals.merge(date, counts, CodexTokenCounts::add);
                }
            });
        }
        return totals.entrySet().stream().map((final var entry) -> new DailyUsage(
                ProviderType.CODEX, entry.getKey(), period.zone().getId(), entry.getValue().input(),
                entry.getValue().output(), entry.getValue().cached()
        )).toList();
    }
}
