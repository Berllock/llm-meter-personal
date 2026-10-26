package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.dto.CodexCollectionIssue;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;

public class CodexUsageExample {

    public static void main(final String[] args) {
        if (args.length != 1 && args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Provide a file/directory path, or a directory path followed by from, to, and optional zone"
            );
        }
        final var source = Path.of(args[0]);
        final var inspector = new CodexDirectoryInspector();
        try {
            if (args.length > 1) {
                final var period = new UsagePeriod(LocalDate.parse(args[1]), LocalDate.parse(args[2]),
                        ZoneId.of(args.length == 4 ? args[3] : "UTC"));
                final var report = inspector.inspectDaily(source, period);
                System.out.println("Discovered files: " + report.discoveredFiles());
                System.out.println("Individually processable files: " + report.processedFiles());
                System.out.println("Daily period: " + period.from() + " to " + period.to() + " (" + period.zone() + ")");
                printIssues(report.issues());
                if (report.usage() == null) {
                    System.out.println("Daily aggregate unavailable; no partial totals were produced.");
                } else {
                    System.out.println("Daily usage: " + report.usage());
                }
            } else if (Files.isDirectory(source)) {
                final var report = inspector.inspect(source);
                System.out.println("Discovered files: " + report.discoveredFiles());
                System.out.println("Readable files: " + report.readableFiles());
                System.out.println("Individually processable files: " + report.processableFiles());
                System.out.println("Related sessions: " + report.relatedSessions());
                System.out.println("Attributed related sessions: " + report.attributedRelatedSessions());
                System.out.println("Unidentified sessions: " + report.unidentifiedSessions());
                printIssues(report.diagnostics());
                if (report.usage() == null) {
                    System.out.println("Aggregate unavailable; no partial totals were produced.");
                } else {
                    System.out.println(report.usage());
                }
            } else {
                System.out.println(new CodexAdapter(source).getUsage());
            }
        } catch (final ProviderCollectionException e) {
            System.out.println("Collection unavailable [" + e.failure() + "]: " + e.failure().description());
            System.out.println("No complete result was produced.");
        }
    }

    private static void printIssues(final List<CodexCollectionIssue> issues) {
        final var categories = new EnumMap<ProviderCollectionFailure, Integer>(ProviderCollectionFailure.class);
        for (final var issue : issues) {
            categories.merge(issue.category(), 1, Integer::sum);
        }
        System.out.println("Blocking issues: " + issues.size());
        System.out.println("Issue groups: " + categories);
        for (final var issue : issues) {
            System.out.println(issue.summary());
        }
    }
}
