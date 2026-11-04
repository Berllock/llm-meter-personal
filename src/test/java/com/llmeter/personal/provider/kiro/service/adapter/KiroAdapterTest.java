package com.llmeter.personal.provider.kiro.service.adapter;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class KiroAdapterTest {
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), ZoneId.of("America/Sao_Paulo"));
    private Path session(final Path root, final String id, final String messages) throws Exception {
        final var folder = Files.createDirectories(root.resolve(id));
        Files.writeString(folder.resolve("session.json"), "{\"schemaVersion\":\"1.0.0\",\"dataModelVersion\":1,\"id\":\"" + id + "\"}");
        Files.writeString(folder.resolve("messages.jsonl"), messages);
        return folder;
    }
    // Validates workspace-grouped native sessions are discovered and matching session copies are counted once.
    @Test
    void shouldDiscoverWorkspaceGroups(@TempDir final Path root) throws Exception {
        final var workspace = Files.createDirectory(root.resolve("workspace-a"));
        session(workspace, "session-a", event("event-a", "0.1"));
        final var copy = Files.createDirectory(root.resolve("workspace-b"));
        session(copy, "session-a", event("event-a", "0.1"));
        final var adapter = new KiroAdapter(root);
        assertEquals("0.1", adapter.getDailyUsageReport(period).usage().getFirst().credits());
        assertEquals(1, adapter.getDailyUsageReport(period).coverage().observedSessions());
    }

    private String event(final String id, final String amount) {
        return "{\"id\":\"" + id + "\",\"timestamp\":\"2026-11-03T01:00:00Z\",\"payload\":{\"type\":\"usage_summary\",\"executionId\":\"" + id + "\",\"status\":\"success\",\"promptTurnSummaries\":[{\"unit\":\"credit\",\"usage\":" + amount + "}]}}\n";
    }

    // Validates daily decimal aggregation, time-zone attribution, pending transitions and permanent token unavailability.
    @Test
    void shouldAggregateCreditsWithoutInventingTokens(@TempDir final Path root) throws Exception {
        session(root, "session-a", event("event-a", "0.1"));
        final var second = session(root, "session-b", "");
        final var adapter = new KiroAdapter(root);
        final var first = adapter.getDailyUsageReport(period);
        assertFalse(first.coverage().tokenCountsAvailable());
        assertEquals(1, first.coverage().pendingSessions());
        assertEquals("0.1", first.usage().getFirst().credits());
        assertNull(first.usage().getFirst().inputTokens());
        assertThrows(ProviderCollectionException.class, () -> adapter.getDailyUsage(period));
        Files.writeString(second.resolve("messages.jsonl"), event("event-b", "0.2"));
        final var updated = adapter.getDailyUsageReport(period);
        assertEquals("0.3", updated.usage().getFirst().credits());
        assertEquals(0, updated.coverage().pendingSessions());
        assertEquals("0.3", adapter.getUsage().credits());
        assertNull(adapter.getUsage().outputTokens());
        assertTrue(adapter.getQuota().isEmpty());
    }

    // Validates inherited identical event IDs are counted once and conflicting copies fail even outside the date filter.
    @Test
    void shouldReconcileCreditEventsBeforeFiltering(@TempDir final Path root) throws Exception {
        session(root, "session-a", event("event-a", "0.1"));
        final var second = session(root, "session-b", event("event-a", "0.1"));
        final var adapter = new KiroAdapter(root);
        assertEquals("0.1", adapter.getDailyUsageReport(period).usage().getFirst().credits());
        Files.writeString(second.resolve("messages.jsonl"), event("event-a", "0.2"));
        final var outside = new UsagePeriod(LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-01"), ZoneId.of("UTC"));
        assertThrows(ProviderCollectionException.class, () -> adapter.getDailyUsageReport(outside));
    }

    // Validates an empty source remains sparse, while orphan messages cannot masquerade as an empty successful source.
    @Test
    void shouldRejectOrphanTranscripts(@TempDir final Path root) throws Exception {
        final var adapter = new KiroAdapter(root);
        assertTrue(adapter.getDailyUsageReport(period).usage().isEmpty());
        assertNull(adapter.getUsage().credits());
        final var folder = Files.createDirectory(root.resolve("orphan"));
        Files.writeString(folder.resolve("messages.jsonl"), event("event-a", "1"));
        assertThrows(ProviderCollectionException.class, () -> adapter.getDailyUsageReport(period));
    }
}
