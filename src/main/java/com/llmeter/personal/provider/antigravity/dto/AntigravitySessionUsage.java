package com.llmeter.personal.provider.antigravity.dto;

import com.llmeter.personal.provider.antigravity.domain.AntigravityTokenCounts;
import java.time.LocalDate;
import java.util.Map;

/** A projection of recorded model steps, without prompt/response text or credentials. */
public record AntigravitySessionUsage(Map<LocalDate, AntigravityTokenCounts> days, boolean observed) {
    public AntigravitySessionUsage {
        days = Map.copyOf(days);
    }
}
