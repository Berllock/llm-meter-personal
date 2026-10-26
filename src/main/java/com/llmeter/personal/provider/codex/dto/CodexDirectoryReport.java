package com.llmeter.personal.provider.codex.dto;

import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.util.List;

public record CodexDirectoryReport(
        int discoveredFiles,
        int readableFiles,
        int relatedSessions,
        int attributedRelatedSessions,
        int unidentifiedSessions,
        UsageSnapshot usage,
        List<String> issues
) {
    public CodexDirectoryReport {
        issues = List.copyOf(issues);
    }
}
