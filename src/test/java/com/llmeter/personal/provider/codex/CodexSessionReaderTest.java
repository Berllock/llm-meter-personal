package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import com.llmeter.personal.provider.domain.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexSessionReaderTest {

    // Verifies that session metadata is returned with the final usage event rather than the sum of events.
    @Test
    void shouldReadSessionIdentityAndLatestUsage() throws Exception {
        final var resource = Objects.requireNonNull(
                getClass().getResource("/codex/session.jsonl"), "Test resource not found"
        );
        final var reader = new CodexSessionReader();

        final var result = reader.read(Path.of(resource.toURI()));

        assertEquals("session-test-001", result.sessionId());
        assertEquals("parent-session-test-001", result.parentThreadId());
        assertNull(result.forkedFromId());
        assertEquals(ProviderType.CODEX, result.usage().provider());
        assertEquals(Long.valueOf(180L), result.usage().inputTokens());
        assertEquals(Long.valueOf(70L), result.usage().outputTokens());
        assertEquals(Long.valueOf(30L), result.usage().cachedTokens());
        assertNotNull(result.usage().collectedAt());
    }

    // Verifies that files without metadata retain their usage without inventing session identifiers.
    @Test
    void shouldPreserveUsageWhenSessionMetadataIsMissing(@TempDir final Path directory) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                """);
        final var reader = new CodexSessionReader();

        final var result = reader.read(file);

        assertNull(result.sessionId());
        assertNull(result.parentThreadId());
        assertNull(result.forkedFromId());
        assertEquals(Long.valueOf(100L), result.usage().inputTokens());
        assertNull(result.usage().outputTokens());
        assertNull(result.usage().cachedTokens());
    }

    // Verifies that a session can have an identifier without a parent, whether the parent is absent or null.
    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"session-root\"}", "{\"id\":\"session-root\",\"parent_thread_id\":null}"})
    void shouldReadSessionWithoutParent(
            final String metadata,
            @TempDir final Path directory
    ) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"session_meta","payload":%s}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":0}}}}
                """.formatted(metadata));
        final var reader = new CodexSessionReader();

        final var result = reader.read(file);

        assertEquals("session-root", result.sessionId());
        assertNull(result.parentThreadId());
        assertEquals(Long.valueOf(0L), result.usage().inputTokens());
    }

    // Verifies that numeric or blank session identifiers are rejected rather than converted or invented.
    @ParameterizedTest
    @CsvSource(textBlock = """
            id, 123
            id, ""
            parent_thread_id, 123
            parent_thread_id, " "
            forked_from_id, 123
            forked_from_id, ""
            """)
    void shouldRejectInvalidIdentifiers(
            final String field,
            final String value,
            @TempDir final Path directory
    ) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"session_meta","payload":{"%s":%s}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                """.formatted(field, value));
        final var reader = new CodexSessionReader();

        final var exception = assertThrows(ProviderCollectionException.class, () -> reader.read(file));

        assertEquals("Invalid session identifier for field " + field, exception.getMessage());
    }

    // Verifies that inconsistent metadata cannot silently attribute one file's usage to a different session or parent.
    @ParameterizedTest
    @ValueSource(strings = {"id", "parent_thread_id", "forked_from_id"})
    void shouldRejectConflictingIdentifiers(
            final String field,
            @TempDir final Path directory
    ) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"session_meta","payload":{"id":"session-a","parent_thread_id":"parent-a","forked_from_id":"origin-a"}}
                {"type":"session_meta","payload":{"%s":"different"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                """.formatted(field));
        final var reader = new CodexSessionReader();

        assertThrows(ProviderCollectionException.class, () -> reader.read(file));
    }

    // Verifies that fork origin and shared session metadata do not replace the thread's own identifier.
    @Test
    void shouldReadForkOriginSeparatelyFromSessionIdentity(@TempDir final Path directory) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"session_meta","payload":{"id":"fork-thread","session_id":"shared-session","forked_from_id":"origin-thread"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":10}}}}
                """);
        final var reader = new CodexSessionReader();

        final var result = reader.read(file);

        assertEquals("fork-thread", result.sessionId());
        assertNull(result.parentThreadId());
        assertEquals("origin-thread", result.forkedFromId());
        assertEquals(Long.valueOf(10L), result.usage().inputTokens());
    }
}
