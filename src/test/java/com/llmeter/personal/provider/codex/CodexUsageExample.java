package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;

import java.nio.file.Path;

public class CodexUsageExample {

    public static void main(final String[] args){

        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Provide exactly one argument: the session JSONL file path"
            );
        }

        final var sessionFile = Path.of(args[0]);
        final LlmProviderAdapter adapter = new CodexAdapter(sessionFile);
        final var snapshot = adapter.getUsage();

        System.out.println(snapshot);
    }

}
