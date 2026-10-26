package com.llmeter.personal.provider.codex.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class CodexUsageExampleTest {

    @TempDir
    Path directory;

    // Verifies category counts, safe numbered file references, and the absence of partial daily totals in CLI output.
    @Test
    void shouldPrintSafeDailyBlockerSummary() throws Exception {
        Files.writeString(directory.resolve("a-private.jsonl"), "{\"PRIVATE_CONTENT\":");
        Files.writeString(directory.resolve("b-private.jsonl"), "{\"type\":\"session_meta\",\"payload\":{\"id\":\"PRIVATE_ID\",\"history_mode\":\"paginated\"}}");
        copyParent();

        final var output = run(directory.toString(), "2026-10-24", "2026-10-26");

        assertThat(output).contains("Discovered files: 3", "Individually processable files: 1", "Blocking issues: 2",
                "NO_TOKEN_COUNT=1", "INVALID_JSON=1", "Files [1] [INVALID_JSON]", "Files [2] [NO_TOKEN_COUNT]",
                "Daily aggregate unavailable; no partial totals were produced.")
                .doesNotContain("Daily usage:", "PRIVATE_CONTENT", "PRIVATE_ID", "a-private.jsonl", directory.toString());
    }

    // Verifies that daily command arguments select UTC by default and print a complete validated result.
    @Test
    void shouldPrintDailyUsageWithDefaultZone() throws Exception {
        copyParent();

        final var output = run(directory.toString(), "2026-10-24", "2026-10-26");

        assertThat(output).contains("Blocking issues: 0", "(UTC)", "Daily usage:", "date=2026-10-25", "inputTokens=160")
                .doesNotContain("unavailable", directory.toString());
    }

    // Verifies that the optional zone changes daily grouping while preserving the validated session totals.
    @Test
    void shouldAcceptExplicitDailyZone() throws Exception {
        copyParent();

        final var output = run(directory.toString(), "2026-10-24", "2026-10-26", "America/Sao_Paulo");

        assertThat(output).contains("(America/Sao_Paulo)", "date=2026-10-24", "inputTokens=100", "inputTokens=60");
    }

    // Verifies that missing single-file and directory sources produce a safe classified error instead of a private stack trace.
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void shouldPrintSafeDiscoveryOrReadFailure(final int arguments) {
        final var missing = directory.resolve("private-missing").toString();
        final var output = arguments == 1 ? run(missing) : run(missing, "2026-10-24", "2026-10-26");

        assertThat(output).contains("Collection unavailable [READ_FAILURE]", "No complete result was produced.")
                .doesNotContain(missing, "NoSuchFileException", "at com.llmeter");
    }

    // Verifies that the original one-directory command retains cumulative collection and exposes classified diagnostics.
    @Test
    void shouldPreserveCumulativeCommand() throws Exception {
        copyParent();

        final var output = run(directory.toString());

        assertThat(output).contains("Readable files: 1", "Individually processable files: 1", "Blocking issues: 0",
                "UsageSnapshot[provider=CODEX", "inputTokens=160");
    }

    private void copyParent() throws Exception {
        final var resource = Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl"));
        Files.copy(Path.of(resource.toURI()), directory.resolve("z-valid.jsonl"));
    }

    private String run(final String... args) {
        final var original = System.out;
        final var buffer = new ByteArrayOutputStream();
        try (final var output = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            CodexUsageExample.main(args);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
