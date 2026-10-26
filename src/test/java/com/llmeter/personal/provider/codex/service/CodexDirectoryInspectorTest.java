package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexDirectoryInspectorTest {

    // Verifies that a valid directory reports counts and a complete aggregate with duplicate sessions counted once.
    @Test
    void shouldReportCompleteAggregateForValidDirectory(@TempDir final Path directory) throws IOException {
        final var nested = Files.createDirectory(directory.resolve("nested"));
        final var first = writeSession(directory, "first.jsonl", "{\"id\":\"session-a\"}", 100);
        Files.copy(first, nested.resolve("copy.jsonl"));
        writeSession(nested, "second.jsonl", "{\"id\":\"session-b\"}", 200);
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.discoveredFiles()).isEqualTo(3);
        assertThat(report.readableFiles()).isEqualTo(3);
        assertThat(report.relatedSessions()).isZero();
        assertThat(report.unidentifiedSessions()).isZero();
        assertThat(report.issues()).isEmpty();
        assertThat(report.usage()).isNotNull();
        assertThat(report.usage().inputTokens()).isEqualTo(300L);
        assertThat(report.usage().outputTokens()).isEqualTo(80L);
        assertThat(report.usage().cachedTokens()).isNull();
    }

    // Verifies that unsupported parent and fork relationships are visible without publishing a misleading aggregate.
    @ParameterizedTest
    @ValueSource(strings = {"parent_thread_id", "forked_from_id"})
    void shouldReportRelatedSessions(final String field, @TempDir final Path directory) throws IOException {
        writeSession(directory, "session.jsonl", "{\"id\":\"child\",\"%s\":\"origin\"}".formatted(field), 100);
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.readableFiles()).isEqualTo(1);
        assertThat(report.relatedSessions()).isEqualTo(1);
        assertThat(report.usage()).isNull();
        assertThat(report.issues()).hasSize(1);
        assertThat(report.issues().getFirst()).contains("inherited usage attribution");
    }

    // Verifies that readable but unidentified sessions are reported as a deduplication blocker.
    @Test
    void shouldReportMissingSessionIdentity(@TempDir final Path directory) throws IOException {
        writeSession(directory, "session.jsonl", "{}", 100);
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.readableFiles()).isEqualTo(1);
        assertThat(report.unidentifiedSessions()).isEqualTo(1);
        assertThat(report.usage()).isNull();
        assertThat(report.issues().getFirst()).contains("without an identifier");
    }

    // Verifies that inspection continues after malformed JSON while withholding totals and private file content.
    @Test
    void shouldReportReadFailuresWithoutPublishingPartialTotals(@TempDir final Path directory) throws IOException {
        Files.writeString(directory.resolve("a-private-session.jsonl"), "{\"PRIVATE_CONTENT\":");
        writeSession(directory, "z-valid.jsonl", "{\"id\":\"session-a\"}", 100);
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.discoveredFiles()).isEqualTo(2);
        assertThat(report.readableFiles()).isEqualTo(1);
        assertThat(report.usage()).isNull();
        assertThat(report.issues()).containsExactly("File 1: Invalid JSON in Codex session at line 1");
        assertThat(String.join("\n", report.issues()))
                .doesNotContain("PRIVATE_CONTENT", "a-private-session.jsonl", directory.toString());
    }

    // Verifies that conflicting copies are diagnosed rather than resolved by file order.
    @Test
    void shouldReportConflictingSessionCopies(@TempDir final Path directory) throws IOException {
        writeSession(directory, "first.jsonl", "{\"id\":\"session-a\"}", 100);
        writeSession(directory, "copy.jsonl", "{\"id\":\"session-a\"}", 200);
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.readableFiles()).isEqualTo(2);
        assertThat(report.usage()).isNull();
        assertThat(report.issues()).containsExactly("Aggregation: Conflicting usage for the same Codex session");
    }

    // Verifies that an empty directory has no issues and zero usage for its empty set of sessions.
    @Test
    void shouldReportEmptyDirectory(@TempDir final Path directory) {
        final var inspector = new CodexDirectoryInspector();

        final var report = inspector.inspect(directory);

        assertThat(report.discoveredFiles()).isZero();
        assertThat(report.readableFiles()).isZero();
        assertThat(report.issues()).isEmpty();
        assertThat(report.usage().inputTokens()).isEqualTo(0L);
    }

    // Verifies that an unavailable root directory remains a discovery failure rather than an apparently empty scan.
    @Test
    void shouldRejectUnavailableDirectory(@TempDir final Path directory) {
        final var inspector = new CodexDirectoryInspector();

        assertThrows(ProviderCollectionException.class, () -> inspector.inspect(directory.resolve("missing")));
    }

    private Path writeSession(
            final Path directory, final String name, final String metadata, final long inputTokens
    ) throws IOException {
        return Files.writeString(directory.resolve(name), """
                {"type":"session_meta","payload":%s}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":%d,"output_tokens":40}}}}
                """.formatted(metadata, inputTokens));
    }
}
