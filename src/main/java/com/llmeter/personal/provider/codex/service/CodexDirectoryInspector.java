package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.dto.CodexCollectionIssue;
import com.llmeter.personal.provider.codex.dto.CodexDailyDirectoryReport;
import com.llmeter.personal.provider.codex.dto.CodexDailySessionUsage;
import com.llmeter.personal.provider.codex.dto.CodexDirectoryReport;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionFiles;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CodexDirectoryInspector {

    private final CodexSessionFiles sessionFiles = new CodexSessionFiles();
    private final CodexSessionReader sessionReader = new CodexSessionReader();
    private final CodexSessionAggregator sessionAggregator = new CodexSessionAggregator();
    private final CodexDailyUsageAggregator dailyAggregator = new CodexDailyUsageAggregator();

    public CodexDirectoryReport inspect(final Path directory) {
        final var scan = scan(directory, null);
        UsageSnapshot usage = null;
        if (scan.diagnostics.isEmpty()) {
            try {
                usage = sessionAggregator.aggregate(scan.sessions);
            } catch (final ProviderCollectionException e) {
                scan.issue(List.of(), e, "Aggregation: ");
            }
        }
        return new CodexDirectoryReport(
                scan.discoveredFiles, scan.sessions.size(), scan.relatedSessions, scan.attributedRelatedSessions,
                scan.unidentifiedSessions, usage, scan.messages, scan.processableFiles, scan.diagnostics
        );
    }

    public CodexDailyDirectoryReport inspectDaily(final Path directory, final UsagePeriod period) {
        Objects.requireNonNull(period, "Daily usage period is required");
        final var scan = scan(directory, period);
        List<DailyUsage> usage = null;
        if (scan.diagnostics.isEmpty()) {
            try {
                usage = dailyAggregator.aggregateSessions(scan.dailySessions, period);
            } catch (final ProviderCollectionException e) {
                scan.issue(List.of(), e, "Aggregation: ");
            }
        }
        return new CodexDailyDirectoryReport(scan.discoveredFiles, scan.processableFiles, usage, scan.diagnostics);
    }

    private Scan scan(final Path directory, final UsagePeriod period) {
        final var files = sessionFiles.find(directory);
        final var scan = new Scan(files.size());
        final Map<String, IndexedSession> previousSessions = new HashMap<>();
        int fileNumber = 0;
        for (final var file : files) {
            fileNumber++;
            final CodexSessionUsage session;
            final CodexDailySessionUsage daily;
            try {
                daily = period == null ? null : sessionReader.readDaily(file, period.zone());
                session = daily == null ? sessionReader.read(file) : daily.session();
            } catch (final ProviderCollectionException e) {
                scan.issue(List.of(fileNumber), e, "File " + fileNumber + ": ");
                continue;
            }
            scan.sessions.add(session);
            if (daily != null) {
                scan.dailySessions.add(daily);
            }
            if (session.parentThreadId() != null || session.forkedFromId() != null) {
                scan.relatedSessions++;
                if (session.attributableUsage() != null) {
                    scan.attributedRelatedSessions++;
                }
            }
            if (session.sessionId() == null || session.sessionId().isBlank()) {
                scan.unidentifiedSessions++;
            }
            try {
                // Validate every readable file even if an earlier file already blocked the directory.
                sessionAggregator.aggregate(List.of(session));
            } catch (final ProviderCollectionException e) {
                scan.issue(List.of(fileNumber), e, "File " + fileNumber + ": ");
                continue;
            }
            scan.processableFiles++;
            final var previous = previousSessions.putIfAbsent(
                    session.sessionId(), new IndexedSession(fileNumber, session, daily)
            );
            if (previous != null) {
                try {
                    if (period == null) {
                        sessionAggregator.aggregate(List.of(previous.session(), session));
                    } else {
                        dailyAggregator.aggregateSessions(List.of(previous.daily(), daily), period);
                    }
                } catch (final ProviderCollectionException e) {
                    scan.issue(List.of(previous.fileNumber(), fileNumber), e, "Aggregation: ");
                }
            }
        }
        return scan;
    }

    private record IndexedSession(int fileNumber, CodexSessionUsage session, CodexDailySessionUsage daily) {
    }

    private static final class Scan {
        private final int discoveredFiles;
        private final List<CodexSessionUsage> sessions = new ArrayList<>();
        private final List<CodexDailySessionUsage> dailySessions = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final List<CodexCollectionIssue> diagnostics = new ArrayList<>();
        private int processableFiles;
        private int relatedSessions;
        private int attributedRelatedSessions;
        private int unidentifiedSessions;

        private Scan(final int discoveredFiles) {
            this.discoveredFiles = discoveredFiles;
        }

        private void issue(final List<Integer> files, final ProviderCollectionException error, final String prefix) {
            diagnostics.add(new CodexCollectionIssue(files, error.failure()));
            messages.add(prefix + error.getMessage());
        }
    }
}
