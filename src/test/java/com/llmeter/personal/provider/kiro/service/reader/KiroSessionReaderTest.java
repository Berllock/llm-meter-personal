package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class KiroSessionReaderTest {
    public static final String METADATA = "{\"schemaVersion\":\"1.0.0\",\"dataModelVersion\":1,\"id\":\"session-a\"}";
    public static final String EVENT = """
            {"id":"event-a","timestamp":"2026-11-03T01:00:00Z","payload":{"type":"usage_summary","executionId":"execution-a","status":"success","promptTurnSummaries":[{"unit":"credit","usage":1.234567890123456789}]}}
            """;
    public static Path session(final Path root, final String messages) throws Exception {
        final var folder = Files.createDirectories(root.resolve("session-a"));
        Files.writeString(folder.resolve("session.json"), METADATA);
        Files.writeString(folder.resolve("messages.jsonl"), messages);
        return folder;
    }

    // Validates exact decimal credits, replay deduplication, and exclusion of context percentages from token accounting.
    @Test
    void shouldPreserveExactCreditsAndIgnoreContext(@TempDir final Path root) throws Exception {
        final var folder = session(root, EVENT + EVENT + """
                {"id":"context","timestamp":"2026-11-03T02:00:00Z","payload":{"type":"session_metadata","key":"contextUsage","value":{"usagePercentage":42.5}}}
                """);
        final var result = new KiroSessionReader().read(folder);
        assertEquals(1, result.events().size());
        assertEquals(new BigDecimal("1.234567890123456789"), result.events().getFirst().credits());
    }

    // Validates valid metadata without a usage summary remains pending, including an initializing missing message file.
    @Test
    void shouldRecognizePendingSessions(@TempDir final Path root) throws Exception {
        final var folder = session(root, "");
        assertTrue(new KiroSessionReader().read(folder).events().isEmpty());
        Files.delete(folder.resolve("messages.jsonl"));
        assertTrue(new KiroSessionReader().read(folder).events().isEmpty());
    }

    // Validates unsupported units, malformed values, conflicting copies, invalid timestamps and unsafe credit magnitudes fail explicitly.
    @ParameterizedTest
    @CsvSource({"json,INVALID_JSON", "negative,INVALID_METRICS", "unit,UNSUPPORTED_HISTORY", "missing,INVALID_METRICS",
            "overflow,OVERFLOW", "magnitude,OVERFLOW", "precision,OVERFLOW", "time,INVALID_TIMESTAMP", "conflict,CONFLICTING_DATA", "execution,CONFLICTING_DATA"})
    void shouldRejectUnsafeCreditRecords(final String mutation, final ProviderCollectionFailure expected, @TempDir final Path root) throws Exception {
        final String text = switch (mutation) {
            case "json" -> "{";
            case "negative" -> EVENT.replace("1.234567890123456789", "-1");
            case "unit" -> EVENT.replace("credit", "token");
            case "missing" -> EVENT.replace("\"usage\":1.234567890123456789", "\"usage\":null");
            case "overflow" -> EVENT.replace("1.234567890123456789", "1e19");
            case "magnitude" -> EVENT.replace("1.234567890123456789", "1e2147483647");
            case "precision" -> EVENT.replace("1.234567890123456789", "0.1234567890123456789");
            case "time" -> EVENT.replace("2026-11-03T01:00:00Z", "invalid-time");
            case "conflict" -> EVENT + EVENT.replace("1.234567890123456789", "2");
            case "execution" -> EVENT + EVENT.replace("event-a", "event-b");
            default -> throw new AssertionError();
        };
        final var folder = session(root, text);
        assertEquals(expected, assertThrows(ProviderCollectionException.class, () -> new KiroSessionReader().read(folder)).failure());
    }

    // Validates mismatched session identities and future schemas cannot silently be interpreted as the supported native format.
    @Test
    void shouldValidateMetadata(@TempDir final Path root) throws Exception {
        final var folder = session(root, EVENT);
        Files.writeString(folder.resolve("session.json"), METADATA.replace("session-a", "other-session"));
        assertEquals(ProviderCollectionFailure.INVALID_IDENTITY, assertThrows(ProviderCollectionException.class,
                () -> new KiroSessionReader().read(folder)).failure());
        Files.writeString(folder.resolve("session.json"), METADATA.replace("1.0.0", "2.0.0"));
        assertEquals(ProviderCollectionFailure.UNSUPPORTED_HISTORY, assertThrows(ProviderCollectionException.class,
                () -> new KiroSessionReader().read(folder)).failure());
    }
}
