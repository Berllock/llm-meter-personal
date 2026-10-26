package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
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
        final var sessions = new ArrayList<CodexSessionUsage>();
        final Map<String, Map<LocalDate, CodexTokenCounts>> uniqueDays = new LinkedHashMap<>();
        for (final var file : files) {
            final var daily = reader.readDaily(file, period.zone());
            final var session = daily.session();
            sessions.add(session);
            final var days = daily.days();
            final var previous = uniqueDays.putIfAbsent(session.sessionId(), days);
            if (previous != null && !previous.equals(days)) {
                throw new ProviderCollectionException("Conflicting daily history for the same Codex session");
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
