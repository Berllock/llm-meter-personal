package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import com.llmeter.personal.provider.domain.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CodexAdapterTest {

    // Verifies that the expected token metrics can be read from a JSON event.
    @Test
    void shouldReadTokenCountsFromEvent() {
        final var line = """
                {
                    "type": "event_msg",
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {
                                "input_tokens": 100,
                                "output_tokens": 40,
                                "cached_input_tokens": 20
                            }
                        }
                    }
                }
                """;
        final var mapper = new JsonMapper();

        final var event = mapper.readTree(line);
        final var tokenUsage = event
                .path("payload")
                .path("info")
                .path("total_token_usage");

        assertEquals(100L, tokenUsage.path("input_tokens").asLong());
        assertEquals(40L, tokenUsage.path("output_tokens").asLong());
        assertEquals(20L, tokenUsage.path("cached_input_tokens").asLong());
    }

    // Verifies that the final token usage event supplies the session snapshot.
    @Test
    void shouldReturnUsageFromLastSessionEvent() throws Exception {
        final var resource = Objects.requireNonNull(
                getClass().getResource("/codex/session.jsonl"),
                "Test resource not found"
        );
        final var file = Path.of(resource.toURI());
        final var adapter = new CodexAdapter(file);

        final var result = adapter.getUsage();

        assertEquals(ProviderType.CODEX, result.provider());
        assertEquals(Long.valueOf(180L), result.inputTokens());
        assertEquals(Long.valueOf(70L), result.outputTokens());
        assertEquals(Long.valueOf(30L), result.cachedTokens());
    }

    // Verifies that a session without usage reports a collection failure.
    @Test
    void shouldRejectSessionWithoutTokenCounts(@TempDir final Path directory) throws IOException {
        final var file = Files.createFile(directory.resolve("empty.jsonl"));
        final var adapter = new CodexAdapter(file);

        assertThrows(ProviderCollectionException.class, adapter::getUsage);
    }

    // Verifies that a file read failure preserves its original cause.
    @Test
    void shouldPreserveCauseWhenSessionCannotBeRead(@TempDir final Path directory) {
        final var adapter = new CodexAdapter(directory.resolve("missing.jsonl"));

        final var exception = assertThrows(ProviderCollectionException.class, adapter::getUsage);

        assertInstanceOf(NoSuchFileException.class, exception.getCause());
    }

    // Verifies that unrelated events are skipped and unavailable metrics remain null.
    @Test
    void shouldSkipUnrelatedEventsAndPreserveUnavailableMetrics(@TempDir final Path directory)
            throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """

                {"type":"event_msg","payload":{"type":"other"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":0,"output_tokens":null}}}}
                {"type":"event_msg","payload":{"type":"token_count","info":null}}
                """);
        final var adapter = new CodexAdapter(file);

        final var result = adapter.getUsage();

        assertEquals(Long.valueOf(0L), result.inputTokens());
        assertNull(result.outputTokens());
        assertNull(result.cachedTokens());
    }

    // Verifies that negative token counts report a collection failure.
    @Test
    void shouldRejectNegativeTokenCounts(@TempDir final Path directory)
            throws IOException {
        final var file = directory.resolve("session.jsonl");

        Files.writeString(file, """
            {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":-10,"output_tokens":40,"cached_input_tokens":20}}}}
            """);

        final var adapter = new CodexAdapter(file);

        assertThrows(ProviderCollectionException.class, adapter::getUsage);
    }

    // Verifies that text, fractional, and out-of-range counts identify the invalid field.
    @ParameterizedTest
    @CsvSource(textBlock = """
            input_tokens, "100"
            input_tokens, 1.5
            input_tokens, 9223372036854775808
            output_tokens, "100"
            output_tokens, 1.5
            output_tokens, 9223372036854775808
            cached_input_tokens, "100"
            cached_input_tokens, 1.5
            cached_input_tokens, 9223372036854775808
            """)
    void shouldRejectInvalidTokenCounts(
            final String field,
            final String tokenValue,
            @TempDir final Path directory
    ) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"%s":%s}}}}
                """.formatted(field, tokenValue));
        final var adapter = new CodexAdapter(file);

        final var exception = assertThrows(ProviderCollectionException.class, adapter::getUsage);

        assertEquals("Invalid token count for field " + field, exception.getMessage());
    }

    // Verifies that zero and the maximum long value remain valid for every metric.
    @ParameterizedTest
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void shouldAcceptTokenCountsWithinLongRange(
            final long tokens,
            @TempDir final Path directory
    ) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":%1$d,"output_tokens":%1$d,"cached_input_tokens":%1$d}}}}
                """.formatted(tokens));
        final var adapter = new CodexAdapter(file);

        final var result = adapter.getUsage();

        assertEquals(Long.valueOf(tokens), result.inputTokens());
        assertEquals(Long.valueOf(tokens), result.outputTokens());
        assertEquals(Long.valueOf(tokens), result.cachedTokens());
    }

    // Verifies that malformed JSON reports its line and preserves the parser failure.
    @Test
    void shouldRejectMalformedJson(@TempDir final Path directory) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"event_msg","payload":
                """);
        final var adapter = new CodexAdapter(file);

        final var exception = assertThrows(ProviderCollectionException.class, adapter::getUsage);

        assertEquals("Invalid JSON in Codex session at line 1", exception.getMessage());
        assertInstanceOf(JacksonException.class, exception.getCause());
    }

    // Verifies that physical line numbers include blank lines before malformed JSON.
    @Test
    void shouldReportMalformedJsonLineAfterBlankLineAndValidUsage(@TempDir final Path directory)
            throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """

                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                {"type":"event_msg","payload":
                """);
        final var adapter = new CodexAdapter(file);

        final var exception = assertThrows(ProviderCollectionException.class, adapter::getUsage);

        assertEquals("Invalid JSON in Codex session at line 3", exception.getMessage());
        assertInstanceOf(JacksonException.class, exception.getCause());
    }

    // Verifies that an empty directory produces one Codex snapshot with zero counters.
    @Test
    void shouldCollectEmptyDirectory(@TempDir final Path directory) {
        final var adapter = CodexAdapter.forDirectory(directory);

        final var result = adapter.getUsage();

        assertEquals(ProviderType.CODEX, result.provider());
        assertEquals(Long.valueOf(0L), result.inputTokens());
        assertEquals(Long.valueOf(0L), result.outputTokens());
        assertEquals(Long.valueOf(0L), result.cachedTokens());
    }

    // Verifies that each collection discovers newly created session files rather than reusing a stale file list.
    @Test
    void shouldDiscoverNewSessionsOnEachCollection(@TempDir final Path directory) throws IOException {
        Files.writeString(directory.resolve("first.jsonl"), """
                {"type":"session_meta","payload":{"id":"session-a"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"output_tokens":40,"cached_input_tokens":20}}}}
                """);
        final var adapter = CodexAdapter.forDirectory(directory);
        final var firstResult = adapter.getUsage();
        Files.writeString(directory.resolve("second.jsonl"), """
                {"type":"session_meta","payload":{"id":"session-b"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":200,"output_tokens":60,"cached_input_tokens":30}}}}
                """);

        final var secondResult = adapter.getUsage();

        assertEquals(Long.valueOf(100L), firstResult.inputTokens());
        assertEquals(Long.valueOf(300L), secondResult.inputTokens());
        assertEquals(Long.valueOf(100L), secondResult.outputTokens());
        assertEquals(Long.valueOf(50L), secondResult.cachedTokens());
    }

    // Verifies that directory collection rejects related sessions instead of silently omitting their usage.
    @Test
    void shouldRejectRelatedSessionsInDirectory(@TempDir final Path directory) throws IOException {
        Files.writeString(directory.resolve("session.jsonl"), """
                {"type":"session_meta","payload":{"id":"child","parent_thread_id":"parent"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                """);
        final var adapter = CodexAdapter.forDirectory(directory);

        assertThrows(ProviderCollectionException.class, adapter::getUsage);
    }

    // Verifies that a malformed file aborts directory collection even when another session can be read.
    @Test
    void shouldAbortDirectoryCollectionWhenOneFileIsMalformed(@TempDir final Path directory) throws IOException {
        Files.writeString(directory.resolve("a-valid.jsonl"), """
                {"type":"session_meta","payload":{"id":"session-a"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                """);
        Files.writeString(directory.resolve("z-malformed.jsonl"), "{\"payload\":");
        final var adapter = CodexAdapter.forDirectory(directory);

        final var exception = assertThrows(ProviderCollectionException.class, adapter::getUsage);

        assertInstanceOf(JacksonException.class, exception.getCause());
    }
}
