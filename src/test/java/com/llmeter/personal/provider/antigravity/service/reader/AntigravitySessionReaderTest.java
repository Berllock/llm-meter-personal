package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class AntigravitySessionReaderTest {
    private static final String HEADER = """
            {"step_index":0,"type":"USER_INPUT","source":"USER_EXPLICIT","status":"DONE","created_at":"2026-11-01T23:00:00Z","content":"Synthetic prompt"}
            """;
    private static final String STEP = """
            {"step_index":1,"type":"PLANNER_RESPONSE","source":"MODEL","status":"DONE","created_at":"2026-11-02T01:00:00Z","input_tokens":20,"cache_read_tokens":80,"output_tokens":10,"thinking":"Synthetic response"}
            """;

    // Validates per-step counts, cache-inclusive normalization, local-day attribution, and exact replay deduplication.
    @Test
    void shouldNormalizeAndDeduplicateSteps(@TempDir final Path root) throws Exception {
        final var result = read(root, HEADER + STEP + STEP, "America/Sao_Paulo");
        final var counts = result.days().get(LocalDate.parse("2026-11-01"));
        assertTrue(result.observed());
        assertEquals(100L, counts.input());
        assertEquals(80L, counts.cached());
        assertEquals(10L, counts.output());
        assertEquals(1, result.days().size());
    }

    // Validates missing counters stay unknown, including canonical input when the cached portion is unavailable.
    @Test
    void shouldPreserveUnknownMetrics(@TempDir final Path root) throws Exception {
        final var result = read(root, HEADER + STEP.replace("\"cache_read_tokens\":80,", ""), "UTC");
        final var counts = result.days().values().iterator().next();
        assertNull(counts.input());
        assertNull(counts.cached());
        assertEquals(10L, counts.output());
    }

    // Validates an identified conversation without model counters remains pending rather than becoming zero usage.
    @Test
    void shouldReturnPendingConversation(@TempDir final Path root) throws Exception {
        final var result = read(root, HEADER, "UTC");
        assertFalse(result.observed());
        assertTrue(result.days().isEmpty());
    }

    // Validates an input created before the preceding response does not invalidate increasing step indices.
    @Test
    void shouldAcceptOverlappingEventTimestamps(@TempDir final Path root) throws Exception {
        final String input = """
                {"step_index":2,"type":"USER_INPUT","source":"USER_EXPLICIT","status":"DONE","created_at":"2026-11-02T00:59:19Z"}
                """;
        final var result = read(root, HEADER + STEP + input + STEP.replace("\"step_index\":1", "\"step_index\":3"), "UTC");
        final var counts = result.days().get(LocalDate.parse("2026-11-02"));
        assertTrue(result.observed());
        assertEquals(200L, counts.input());
        assertEquals(160L, counts.cached());
        assertEquals(20L, counts.output());
        assertEquals(1, result.days().size());
    }

    // Validates model usage is attributed to its own event date even when timestamps differ from index order.
    @Test
    void shouldAttributeOutOfTimeOrderCountsToTheirOwnDay(@TempDir final Path root) throws Exception {
        final String earlier = STEP.replace("\"step_index\":1", "\"step_index\":2")
                .replace("2026-11-02T01:00:00Z", "2026-11-01T23:30:00Z");
        final var result = read(root, HEADER + STEP + earlier + earlier, "UTC");
        assertEquals(2, result.days().size());
        assertEquals(100L, result.days().get(LocalDate.parse("2026-11-01")).input());
        assertEquals(100L, result.days().get(LocalDate.parse("2026-11-02")).input());
        assertEquals(10L, result.days().get(LocalDate.parse("2026-11-01")).output());
    }

    // Validates malformed, unsupported, conflicting, missing-counter, invalid-time, and overflow histories fail explicitly.
    @ParameterizedTest
    @CsvSource({"json,INVALID_JSON", "negative,INVALID_METRICS", "fraction,INVALID_METRICS", "overflow,OVERFLOW",
            "timestamp,INVALID_TIMESTAMP", "conflict,CONFLICTING_DATA", "identity,INVALID_IDENTITY",
            "unsupported,UNSUPPORTED_HISTORY", "missing,NO_TOKEN_COUNT", "sequence,INVALID_SEQUENCE"})
    void shouldRejectUnsafeSteps(final String mutation, final ProviderCollectionFailure expected, @TempDir final Path root) throws Exception {
        final String text = switch (mutation) {
            case "json" -> HEADER + "{";
            case "negative" -> HEADER + STEP.replace("\"input_tokens\":20", "\"input_tokens\":-1");
            case "fraction" -> HEADER + STEP.replace("\"input_tokens\":20", "\"input_tokens\":1.5");
            case "overflow" -> HEADER + STEP.replace("\"input_tokens\":20", "\"input_tokens\":9223372036854775807");
            case "timestamp" -> HEADER + STEP.replace("2026-11-02T01:00:00Z", "not-a-time");
            case "conflict" -> HEADER + STEP + STEP.replace("\"output_tokens\":10", "\"output_tokens\":11");
            case "identity" -> STEP;
            case "unsupported" -> HEADER + STEP.replace("PLANNER_RESPONSE", "UNRECOGNIZED_RESPONSE");
            case "missing" -> HEADER + STEP.replace("\"input_tokens\":20,\"cache_read_tokens\":80,\"output_tokens\":10,", "");
            case "sequence" -> HEADER + STEP.replace("\"step_index\":1", "\"step_index\":2") + STEP;
            default -> throw new AssertionError();
        };
        final var error = assertThrows(ProviderCollectionException.class, () -> read(root, text, "UTC"));
        assertEquals(expected, error.failure());
    }

    private com.llmeter.personal.provider.antigravity.dto.AntigravitySessionUsage read(final Path root, final String text, final String zone) throws Exception {
        final var file = root.resolve("synthetic.jsonl");
        Files.writeString(file, text);
        return new AntigravitySessionReader().read(file, ZoneId.of(zone));
    }
}
