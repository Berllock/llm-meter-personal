package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;

import java.nio.file.Files;
import java.nio.file.Path;

public class CodexUsageExample {

    public static void main(final String[] args){

        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Provide exactly one argument: a session JSONL file or sessions directory path"
            );
        }

        final var sessionFile = Path.of(args[0]);
        if (Files.isDirectory(sessionFile)) {
            final var report = new CodexDirectoryInspector().inspect(sessionFile);
            System.out.println("Discovered files: " + report.discoveredFiles());
            System.out.println("Readable files: " + report.readableFiles());
            System.out.println("Related sessions: " + report.relatedSessions());
            System.out.println("Attributed related sessions: " + report.attributedRelatedSessions());
            System.out.println("Unidentified sessions: " + report.unidentifiedSessions());
            for (final var issue : report.issues()) {
                System.out.println("Issue: " + issue);
            }
            if (report.usage() != null) {
                System.out.println(report.usage());
            } else {
                System.out.println("Aggregate unavailable; no partial totals were produced.");
            }
            return;
        }
        final LlmProviderAdapter adapter = new CodexAdapter(sessionFile);
        final var snapshot = adapter.getUsage();

        System.out.println(snapshot);
    }

}
