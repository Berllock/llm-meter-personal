package com.llmeter.personal.provider.codex.dto;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;

import java.time.LocalDate;
import java.util.Map;

public record CodexDailySessionUsage(
        CodexSessionUsage session,
        Map<LocalDate, CodexTokenCounts> days
) {
    public CodexDailySessionUsage {
        days = Map.copyOf(days);
    }
}
