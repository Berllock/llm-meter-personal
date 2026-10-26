package com.llmeter.personal.provider.codex;

import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

public record CodexSessionUsage(
        String sessionId,
        String parentThreadId,
        String forkedFromId,
        UsageSnapshot usage,
        UsageSnapshot attributableUsage
) {
    public CodexSessionUsage(
            final String sessionId, final String parentThreadId, final String forkedFromId,
            final UsageSnapshot usage
    ) {
        this(sessionId, parentThreadId, forkedFromId, usage, null);
    }
}
