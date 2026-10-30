package com.llmeter.personal.provider.codex.dto;

/** A validated identity with either recorded usage or a pending absence of counters. */
public record CodexDailyObservation(
        String sessionId, String parentThreadId, String forkedFromId, CodexDailySessionUsage dailyUsage
) {
}
