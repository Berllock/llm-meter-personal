package com.llmeter.personal.provider.codex.dto;

import com.llmeter.personal.usage.dto.DailyUsage;

import java.util.List;

public record CodexDailyDirectoryReport(
        int discoveredFiles,
        int processedFiles,
        List<DailyUsage> usage,
        List<CodexCollectionIssue> issues
) {
    public CodexDailyDirectoryReport {
        usage = usage == null ? null : List.copyOf(usage);
        issues = List.copyOf(issues);
    }
}
