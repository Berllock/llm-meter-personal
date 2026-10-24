package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.domain.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CodexAdapterTest {

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

    @Test
    void shouldRejectSessionWithoutTokenCounts(@TempDir final Path directory) throws IOException {
        final var file = Files.createFile(directory.resolve("empty.jsonl"));
        final var adapter = new CodexAdapter(file);

        assertThrows(IllegalStateException.class, adapter::getUsage);
    }

    @Test
    void shouldPreserveCauseWhenSessionCannotBeRead(@TempDir final Path directory) {
        final var adapter = new CodexAdapter(directory.resolve("missing.jsonl"));

        final var exception = assertThrows(UncheckedIOException.class, adapter::getUsage);

        assertInstanceOf(NoSuchFileException.class, exception.getCause());
    }

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

    @Test
    void shouldRejectNegativeTokenCounts(@TempDir final Path directory)
            throws IOException {
        final var file = directory.resolve("session.jsonl");

        Files.writeString(file, """
            {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":-10,"output_tokens":40,"cached_input_tokens":20}}}}
            """);

        final var adapter = new CodexAdapter(file);

        assertThrows(IllegalStateException.class, adapter::getUsage);
    }

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

        final var exception = assertThrows(IllegalStateException.class, adapter::getUsage);

        assertEquals("Invalid token count for field " + field, exception.getMessage());
    }

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

    @Test
    void shouldRejectMalformedJson(@TempDir final Path directory) throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """
                {"type":"event_msg","payload":
                """);
        final var adapter = new CodexAdapter(file);

        final var exception = assertThrows(IllegalStateException.class, adapter::getUsage);

        assertEquals("Invalid JSON in Codex session at line 1", exception.getMessage());
        assertInstanceOf(JacksonException.class, exception.getCause());
    }

    @Test
    void shouldReportMalformedJsonLineAfterBlankLineAndValidUsage(@TempDir final Path directory)
            throws IOException {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, """

                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100}}}}
                {"type":"event_msg","payload":
                """);
        final var adapter = new CodexAdapter(file);

        final var exception = assertThrows(IllegalStateException.class, adapter::getUsage);

        assertEquals("Invalid JSON in Codex session at line 3", exception.getMessage());
        assertInstanceOf(JacksonException.class, exception.getCause());
    }
}
