package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.domain.CodexModelDay;
import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;
import com.llmeter.personal.provider.codex.dto.CodexDailySessionUsage;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.ModelDailyUsage;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    List<ModelDailyUsage> aggregateModels(final List<CodexDailySessionUsage> sessions, final UsagePeriod period) {
        // Callers validate copies/lineage through aggregateSessions before exposing these projections.
        final var seen = new HashSet<String>();
        final Map<CodexModelDay, CodexTokenCounts> totals = new HashMap<>();
        for (final var session : sessions) {
            if (!seen.add(session.session().sessionId())) continue;
            session.modelDays().forEach((final var key, final var counts) -> {
                if (period.contains(key.date())) totals.merge(key, counts, CodexTokenCounts::add);
            });
        }
        return totals.entrySet().stream().sorted(Comparator
                .comparing((final Map.Entry<CodexModelDay, CodexTokenCounts> entry) -> entry.getKey().date())
                .thenComparing((final var entry) -> entry.getKey().model(), Comparator.nullsFirst(String::compareTo)))
                .map((final var entry) -> new ModelDailyUsage(ProviderType.CODEX, entry.getKey().date(), period.zone().getId(),
                        entry.getKey().model(), entry.getValue().input(), entry.getValue().output(), entry.getValue().cached())).toList();
    }

    public List<DailyUsage> aggregateSessions(final List<CodexDailySessionUsage> dailySessions, final UsagePeriod period) {
        final var sessions = new ArrayList<CodexSessionUsage>();
        final Map<String, Map<CodexModelDay, CodexTokenCounts>> uniqueModels = new LinkedHashMap<>();
        final Map<String, Map<LocalDate, CodexTokenCounts>> uniqueDays = new LinkedHashMap<>();
        for (final var daily : dailySessions) {
            final var session = daily.session();
            sessions.add(session);
            final var days = daily.days();
            final var previousModels = uniqueModels.putIfAbsent(session.sessionId(), daily.modelDays());
            if (previousModels != null && !previousModels.equals(daily.modelDays())) {
                throw new ProviderCollectionException(ProviderCollectionFailure.CONFLICTING_DATA,
                        "Conflicting daily history (model attribution) for the same Codex session");
            }
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
