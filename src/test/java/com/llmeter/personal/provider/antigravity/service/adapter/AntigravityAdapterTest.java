package com.llmeter.personal.provider.antigravity.service.adapter;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class AntigravityAdapterTest {
    private static final String HEADER = """
            {"step_index":0,"type":"USER_INPUT","source":"USER_EXPLICIT","status":"DONE","created_at":"2026-11-01T12:00:00Z"}
            """;
    private static final String STEP = """
            {"step_index":1,"type":"PLANNER_RESPONSE","source":"MODEL","status":"DONE","created_at":"2026-11-01T12:01:00Z","input_tokens":20,"cache_read_tokens":80,"output_tokens":10}
            """;
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"), ZoneId.of("UTC"));

    // Validates canonical discovery avoids counting full/reduced/chunk copies while retaining pending coverage.
    @Test
    void shouldReadOnlyCanonicalTranscriptsAndRefreshPending(@TempDir final Path root) throws Exception {
        final var full = transcript(root, "session-a", HEADER + STEP);
        Files.writeString(full.resolveSibling("transcript.jsonl"), HEADER + STEP);
        final var chunks = Files.createDirectories(full.getParent().resolve("chunks/transcript_full"));
        Files.writeString(chunks.resolve("00000000.jsonl"), HEADER + STEP);
        final var pending = transcript(root, "session-b", HEADER);
        final var adapter = new AntigravityAdapter(root);
        final var initial = adapter.getDailyUsageReport(period);
        assertEquals(100L, initial.usage().getFirst().inputTokens());
        assertEquals(1, initial.coverage().observedSessions());
        assertEquals(1, initial.coverage().pendingSessions());
        assertThrows(ProviderCollectionException.class, () -> adapter.getDailyUsage(period));
        Files.writeString(pending, HEADER + STEP);
        final var updated = adapter.getDailyUsageReport(period);
        assertEquals(200L, updated.usage().getFirst().inputTokens());
        assertEquals(0, updated.coverage().pendingSessions());
        assertEquals(200L, adapter.getUsage().inputTokens());
        Files.delete(pending);
        assertEquals(100L, adapter.getDailyUsageReport(period).usage().getFirst().inputTokens());
    }

    // Validates an empty directory does not invent a token observation or quota.
    @Test
    void shouldKeepEmptySourceUnavailable(@TempDir final Path root) {
        final var adapter = new AntigravityAdapter(root);
        assertTrue(adapter.getDailyUsageReport(period).usage().isEmpty());
        assertNull(adapter.getUsage().inputTokens());
        assertTrue(adapter.getQuota().isEmpty());
    }

    // Validates date filtering cannot hide corruption elsewhere in the configured source.
    @Test
    void shouldValidateBeforeFiltering(@TempDir final Path root) throws Exception {
        transcript(root, "session-a", HEADER + STEP);
        transcript(root, "session-b", "{");
        final var other = new UsagePeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), ZoneId.of("UTC"));
        assertThrows(ProviderCollectionException.class, () -> new AntigravityAdapter(root).getDailyUsageReport(other));
    }

    // Validates incomplete rolling transcripts cannot silently substitute for complete source history.
    @Test
    void shouldRejectMissingFullTranscript(@TempDir final Path root) throws Exception {
        final var full = transcript(root, "session-a", HEADER);
        Files.move(full, full.resolveSibling("transcript.jsonl"));
        final var error = assertThrows(ProviderCollectionException.class, () -> new AntigravityAdapter(root).getDailyUsageReport(period));
        assertEquals(ProviderCollectionFailure.UNSUPPORTED_HISTORY, error.failure());
    }

    private Path transcript(final Path root, final String name, final String content) throws Exception {
        final var file = root.resolve(name).resolve(".system_generated/logs/transcript_full.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
