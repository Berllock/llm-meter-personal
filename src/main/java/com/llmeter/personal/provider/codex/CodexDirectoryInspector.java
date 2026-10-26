package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CodexDirectoryInspector {

    private final CodexSessionFiles sessionFiles = new CodexSessionFiles();
    private final CodexSessionReader sessionReader = new CodexSessionReader();
    private final CodexSessionAggregator sessionAggregator = new CodexSessionAggregator();

    public CodexDirectoryReport inspect(final Path directory) {
        final var files = sessionFiles.find(directory);
        final List<CodexSessionUsage> sessions = new ArrayList<>();
        final List<String> issues = new ArrayList<>();
        int relatedSessions = 0;
        int attributedRelatedSessions = 0;
        int unidentifiedSessions = 0;
        int fileNumber = 0;

        for (final var file : files) {
            fileNumber++;
            try {
                final var session = sessionReader.read(file);
                sessions.add(session);
                if (session.parentThreadId() != null || session.forkedFromId() != null) {
                    relatedSessions++;
                    if (session.attributableUsage() != null) {
                        attributedRelatedSessions++;
                    }
                }
                if (session.sessionId() == null || session.sessionId().isBlank()) {
                    unidentifiedSessions++;
                }
            } catch (final ProviderCollectionException e) {
                issues.add("File " + fileNumber + ": " + e.getMessage());
            }
        }

        UsageSnapshot usage = null;
        if (issues.isEmpty()) {
            try {
                usage = sessionAggregator.aggregate(sessions);
            } catch (final ProviderCollectionException e) {
                issues.add("Aggregation: " + e.getMessage());
            }
        }

        return new CodexDirectoryReport(
                files.size(), sessions.size(), relatedSessions, attributedRelatedSessions,
                unidentifiedSessions, usage, issues
        );
    }
}
