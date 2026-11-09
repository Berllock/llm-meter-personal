package com.llmeter.personal.provider.codex.dto;

import com.llmeter.personal.provider.codex.domain.CodexModelDay;
import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

public record CodexDailySessionUsage(
        CodexSessionUsage session,
        Map<LocalDate, CodexTokenCounts> days,
        Map<CodexModelDay, CodexTokenCounts> modelDays
) {
    public CodexDailySessionUsage(final CodexSessionUsage session, final Map<LocalDate, CodexTokenCounts> days) {
        this(session, days, days.entrySet().stream().collect(Collectors.toMap(
                (final var entry) -> new CodexModelDay(entry.getKey(), null), Map.Entry::getValue)));
    }
    public CodexDailySessionUsage {
        days = Map.copyOf(days);
        modelDays = Map.copyOf(modelDays);
    }
}
