package com.llmeter.personal.provider.codex;

import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

public record CodexSessionUsage(
        String sessionId,
        String parentThreadId,
        String forkedFromId,
        UsageSnapshot usage
) {
}
