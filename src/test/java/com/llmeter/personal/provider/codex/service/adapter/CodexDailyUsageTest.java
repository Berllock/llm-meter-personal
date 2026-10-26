package com.llmeter.personal.provider.codex.service.adapter;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexDailyUsageTest {

    @TempDir
    Path directory;

    // Verifies local midnight, owned child increments, recursive discovery, file deduplication, and total reconciliation.
    @Test
    void shouldAggregateOwnedDailyIncrements() throws Exception {
        copyFixture("parent", directory.resolve("parent.jsonl"));
        final var nested = Files.createDirectory(directory.resolve("nested"));
        copyFixture("child", nested.resolve("child.jsonl"));
        Files.copy(nested.resolve("child.jsonl"), directory.resolve("copy.jsonl"));
        copyFixture("fresh-child", directory.resolve("fresh.jsonl"));
        copyFixture("inherited-only-child", directory.resolve("inherited.jsonl"));
        final var adapter = CodexAdapter.forDirectory(directory);

        final var days = adapter.getDailyUsage(period("2026-10-24", "2026-10-26", "America/Sao_Paulo"));

        assertThat(days).extracting((final var day) -> day.date().toString())
                .containsExactly("2026-10-24", "2026-10-25", "2026-10-26");
        assertThat(days).extracting((final var day) -> day.inputTokens()).containsExactly(100L, 90L, 30L);
        assertThat(days).extracting((final var day) -> day.outputTokens()).containsExactly(10L, 9L, 3L);
        assertThat(days).extracting((final var day) -> day.cachedTokens()).containsExactly(50L, 45L, 15L);
        assertThat(days).allMatch((final var day) -> day.zone().equals("America/Sao_Paulo"));
        assertThat(days.stream().mapToLong((final var day) -> day.inputTokens()).sum())
                .isEqualTo(adapter.getUsage().inputTokens());
    }

    // Verifies UTC grouping and that unchanged counters on a later day do not create consumption.
    @Test
    void shouldUseRequestedZoneAndIgnoreCounterReemissions() throws Exception {
        final var file = directory.resolve("parent.jsonl");
        copyFixture("parent", file);

        final var days = new CodexAdapter(file).getDailyUsage(period("2026-10-24", "2026-10-26", "UTC"));

        assertThat(days).hasSize(1);
        assertThat(days.getFirst().date()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(days.getFirst().inputTokens()).isEqualTo(160L);
    }

    // Verifies that counters before the inclusive interval are read to establish the correct baseline.
    @Test
    void shouldFilterAfterComputingAllIncrements() throws Exception {
        final var file = directory.resolve("parent.jsonl");
        copyFixture("parent", file);

        final var days = new CodexAdapter(file).getDailyUsage(
                period("2026-10-25", "2026-10-25", "America/Sao_Paulo"));

        assertThat(days).hasSize(1);
        assertThat(days.getFirst().inputTokens()).isEqualTo(60L);
    }

    // Verifies that an empty source or interval without observed increments returns no invented daily records.
    @Test
    void shouldReturnEmptyForUnobservedDays() throws Exception {
        assertThat(CodexAdapter.forDirectory(directory).getDailyUsage(
                period("2026-10-24", "2026-10-26", "UTC"))).isEmpty();
        final var file = directory.resolve("parent.jsonl");
        copyFixture("parent", file);
        assertThat(new CodexAdapter(file).getDailyUsage(period("2026-10-27", "2026-10-28", "UTC"))).isEmpty();
    }

    // Verifies that a missing component remains unknown after it reappears while other increments remain usable.
    @Test
    void shouldPreserveUnknownMetricsAcrossGaps() throws Exception {
        final var content = metadata("")
                + event(2, "2026-10-24T12:00:00Z", counts(100L, 10L, 50L), counts(100L, 10L, 50L))
                + event(3, "2026-10-25T12:00:00Z", counts(150L, 15L, null), counts(50L, 5L, null))
                + event(4, "2026-10-26T12:00:00Z", counts(200L, 20L, 100L), counts(50L, 5L, 25L));
        final var file = write(content);

        final var days = new CodexAdapter(file).getDailyUsage(period("2026-10-24", "2026-10-26", "UTC"));

        assertThat(days).extracting((final var day) -> day.inputTokens()).containsExactly(100L, 50L, 50L);
        assertThat(days).extracting((final var day) -> day.cachedTokens()).containsExactly(50L, null, null);
    }

    // Verifies that exact ordinal replays are ignored, including when followed by a real increment.
    @Test
    void shouldIgnoreExactOrdinalReplay() throws Exception {
        final var first = event(2, "2026-10-25T12:00:00Z", counts(100L, 10L, 50L), counts(100L, 10L, 50L));
        final var file = write(metadata("") + first + first
                + event(3, "2026-10-25T13:00:00Z", counts(120L, 12L, 60L), counts(20L, 2L, 10L)));

        assertThat(new CodexAdapter(file).getDailyUsage(period("2026-10-25", "2026-10-25", "UTC"))
                .getFirst().inputTokens()).isEqualTo(120L);
    }

    // Verifies that malformed timestamps never fall back to collection time or the file name.
    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "2026-10-25T12:00:00", "2026-99-99T00:00:00Z"})
    void shouldRejectInvalidTimestamps(final String timestamp) throws Exception {
        final var file = write(metadata("") + event(2, timestamp, counts(100L, 10L, 50L), counts(100L, 10L, 50L)));

        assertUnavailable(file);
    }

    // Verifies that missing timestamps are rejected even when cumulative snapshot collection still works.
    @Test
    void shouldRejectMissingTimestampWithoutChangingSnapshotBehavior() throws Exception {
        final var file = write(metadata("") + event(2, "2026-10-25T12:00:00Z", counts(100L, 10L, 50L),
                counts(100L, 10L, 50L)).replace("\"timestamp\":\"2026-10-25T12:00:00Z\",", ""));

        assertUnavailable(file);
        assertThat(new CodexAdapter(file).getUsage().inputTokens()).isEqualTo(100L);
    }

    // Verifies that resets, ordinal conflicts/regressions, and backwards timestamps abort the entire query.
    @ParameterizedTest
    @ValueSource(strings = {"reset", "ordinal-conflict", "ordinal-regression", "timestamp-regression", "replay-time-conflict"})
    void shouldRejectAmbiguousProgression(final String scenario) throws Exception {
        final var first = event(2, "2026-10-25T12:00:00Z", counts(100L, 10L, 50L), counts(100L, 10L, 50L));
        final long ordinal = scenario.equals("ordinal-regression") ? 1
                : scenario.equals("ordinal-conflict") || scenario.equals("replay-time-conflict") ? 2 : 3;
        final var time = scenario.equals("timestamp-regression") ? "2026-10-25T11:00:00Z" : "2026-10-25T13:00:00Z";
        final var total = scenario.equals("reset") ? counts(90L, 9L, 45L)
                : scenario.equals("replay-time-conflict") ? counts(100L, 10L, 50L) : counts(120L, 12L, 60L);
        final var file = write(metadata("") + first + event(ordinal, time, total, counts(20L, 2L, 10L)));

        assertUnavailable(file);
    }

    // Verifies that unsupported metadata cannot turn inherited or truncated counters into first-day consumption.
    @ParameterizedTest
    @ValueSource(strings = {"legacy", "external", "fork", "child", "truncated", "missing-last", "late", "repeated", "missing-id", "missing-ordinal"})
    void shouldRejectUnsupportedHistory(final String scenario) throws Exception {
        final var extra = switch (scenario) {
            case "external" -> ",\"history_base\":{}";
            case "fork" -> ",\"forked_from_id\":\"other\"";
            case "child" -> ",\"parent_thread_id\":\"other\"";
            default -> "";
        };
        String header = metadata(extra);
        String token = event(2, "2026-10-25T12:00:00Z", counts(100L, 10L, 50L),
                scenario.equals("truncated") || scenario.equals("child") ? counts(20L, 2L, 10L) : counts(100L, 10L, 50L));
        if (scenario.equals("legacy")) header = header.replace("paginated", "legacy");
        if (scenario.equals("missing-id")) header = header.replace("\"id\":\"test-session\",", "");
        if (scenario.equals("missing-ordinal")) token = token.replace("\"ordinal\":2,", "");
        if (scenario.equals("missing-last")) token = token.replace("\"last_token_usage\":", "\"unused\":");
        final var content = scenario.equals("late") ? token + header
                : scenario.equals("repeated") ? header + token + header : header + token;

        assertUnavailable(write(content));
    }

    // Verifies that a hidden reset across a missing component is rejected when the component reappears.
    @Test
    void shouldDetectResetAcrossUnknownComponent() throws Exception {
        final var file = write(metadata("")
                + event(2, "2026-10-24T12:00:00Z", counts(100L, 10L, 50L), counts(100L, 10L, 50L))
                + event(3, "2026-10-25T12:00:00Z", counts(null, 15L, 75L), counts(null, 5L, 25L))
                + event(4, "2026-10-26T12:00:00Z", counts(90L, 20L, 100L), counts(10L, 5L, 25L)));

        assertUnavailable(file);
    }

    // Verifies that copies with equal final totals but different daily distribution are rejected outside the selected interval too.
    @Test
    void shouldRejectConflictingDailyCopies() throws Exception {
        final var file = directory.resolve("parent.jsonl");
        copyFixture("parent", file);
        Files.writeString(directory.resolve("copy.jsonl"), Files.readString(file)
                .replace("2026-10-25T02:30:00Z", "2026-10-24T02:30:00Z"));

        assertThatThrownBy(() -> CodexAdapter.forDirectory(directory).getDailyUsage(
                period("2026-10-28", "2026-10-28", "UTC")))
                .isInstanceOf(ProviderCollectionException.class).hasMessageContaining("Conflicting daily history");
    }

    // Verifies daily arithmetic overflow is reported rather than wrapping around to a negative count.
    @Test
    void shouldRejectOverflow() throws Exception {
        final var counts = counts(Long.MAX_VALUE, 0L, 0L);
        final var content = metadata("") + event(2, "2026-10-25T12:00:00Z", counts, counts);
        write(content);
        Files.writeString(directory.resolve("other.jsonl"), content.replace("test-session", "other-session"));

        assertThatThrownBy(() -> CodexAdapter.forDirectory(directory).getDailyUsage(
                period("2026-10-25", "2026-10-25", "UTC")))
                .isInstanceOf(ProviderCollectionException.class).hasMessageContaining("exceeds long range");
    }

    private void assertUnavailable(final Path file) {
        assertThatThrownBy(() -> new CodexAdapter(file).getDailyUsage(period("2026-10-24", "2026-10-26", "UTC")))
                .isInstanceOf(ProviderCollectionException.class);
    }

    private Path write(final String content) throws Exception {
        final var file = directory.resolve("session.jsonl");
        Files.writeString(file, content);
        return file;
    }

    private void copyFixture(final String name, final Path target) throws Exception {
        final var resource = Objects.requireNonNull(getClass().getResource("/codex/daily/" + name + ".jsonl"));
        Files.copy(Path.of(resource.toURI()), target);
    }

    private UsagePeriod period(final String from, final String to, final String zone) {
        return new UsagePeriod(LocalDate.parse(from), LocalDate.parse(to), ZoneId.of(zone));
    }

    private String metadata(final String extra) {
        return "{\"type\":\"session_meta\",\"payload\":{\"id\":\"test-session\",\"history_mode\":\"paginated\"" + extra + "}}\n";
    }

    private String counts(final Long input, final Long output, final Long cached) {
        return "{\"input_tokens\":%s,\"output_tokens\":%s,\"cached_input_tokens\":%s}".formatted(input, output, cached);
    }

    private String event(final long ordinal, final String timestamp, final String total, final String last) {
        return """
                {"ordinal":%d,"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":%s,"last_token_usage":%s}}}
                """.formatted(ordinal, timestamp, total, last);
    }
}
