package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.dto.CodexCollectionIssue;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexCollectionDiagnosticsTest {

    @TempDir
    Path directory;

    private final CodexDirectoryInspector inspector = new CodexDirectoryInspector();
    private final UsagePeriod period = new UsagePeriod(
            LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-26"), ZoneId.of("UTC")
    );

    // Verifies precise daily failure categories and file references without returning a partial aggregate.
    @ParameterizedTest
    @CsvSource({
            "empty,NO_TOKEN_COUNT", "json,INVALID_JSON", "missing-time,INVALID_TIMESTAMP",
            "invalid-time,INVALID_TIMESTAMP", "reset,COUNTER_RESET", "child-reset,COUNTER_RESET",
            "ordinal,INVALID_SEQUENCE", "backwards-time,INVALID_SEQUENCE", "legacy,UNSUPPORTED_HISTORY",
            "metric,INVALID_METRICS", "missing-id,INVALID_IDENTITY", "conflicting-meta,CONFLICTING_DATA",
            "hidden-baseline,UNSUPPORTED_HISTORY"
    })
    void shouldClassifyDailyFailures(final String scenario, final ProviderCollectionFailure expected) throws Exception {
        final var first = token(2, "2026-10-25T12:00:00Z", 100);
        final var content = switch (scenario) {
            case "empty" -> header("test", "");
            case "json" -> "{\"PRIVATE_CONTENT\":";
            case "missing-time" -> header("test", "") + first.replace("\"timestamp\":\"2026-10-25T12:00:00Z\",", "");
            case "invalid-time" -> header("test", "") + token(2, "PRIVATE_INVALID_TIME", 100);
            case "reset" -> header("test", "") + first + token(3, "2026-10-25T13:00:00Z", 90);
            case "child-reset" -> header("test", ",\"parent_thread_id\":\"parent\",\"subagent_history_start_ordinal\":10")
                    + token(4, "2026-10-24T12:00:00Z", 100) + token(11, "2026-10-25T12:00:00Z", 150)
                    + token(12, "2026-10-25T13:00:00Z", 130);
            case "ordinal" -> header("test", "") + token(-1, "2026-10-25T12:00:00Z", 100);
            case "backwards-time" -> header("test", "") + first + token(3, "2026-10-25T11:00:00Z", 120);
            case "legacy" -> header("test", "").replace("paginated", "legacy") + first;
            case "metric" -> header("test", "") + token(2, "2026-10-25T12:00:00Z", -1);
            case "missing-id" -> header("test", "").replace("\"id\":\"test\",", "") + first;
            case "conflicting-meta" -> header("test", "") + first + header("other", "");
            case "hidden-baseline" -> header("test", "") + first.replace("\"last_token_usage\":{\"input_tokens\":100", "\"last_token_usage\":{\"input_tokens\":50");
            default -> throw new IllegalArgumentException("Unknown scenario");
        };
        Files.writeString(directory.resolve("private-session.jsonl"), content);

        final var report = inspector.inspectDaily(directory, period);

        assertThat(report.discoveredFiles()).isEqualTo(1);
        assertThat(report.processedFiles()).isZero();
        assertThat(report.usage()).isNull();
        assertThat(report.issues()).containsExactly(new CodexCollectionIssue(List.of(1), expected));
        assertThat(report.issues().getFirst().summary())
                .doesNotContain("PRIVATE_CONTENT", "PRIVATE_INVALID_TIME", "private-session.jsonl", directory.toString());
    }

    // Verifies that unrelated parse failures do not hide unsupported files or conflicting copies later in the scan.
    @Test
    void shouldFindMultipleCumulativeBlockersInOneScan() throws Exception {
        Files.writeString(directory.resolve("a.jsonl"), "{\"PRIVATE_CONTENT\":");
        Files.writeString(directory.resolve("b.jsonl"), header("empty", ""));
        Files.writeString(directory.resolve("c.jsonl"), header("child", ",\"parent_thread_id\":\"parent\"")
                .replace("paginated", "legacy") + token(2, "2026-10-25T12:00:00Z", 100));
        Files.writeString(directory.resolve("d.jsonl"), header("same", "") + token(2, "2026-10-25T12:00:00Z", 100));
        Files.writeString(directory.resolve("e.jsonl"), header("same", "") + token(2, "2026-10-25T12:00:00Z", 200));
        Files.writeString(directory.resolve("f.jsonl"), header("valid", "") + token(2, "2026-10-25T12:00:00Z", 50));

        final var report = inspector.inspect(directory);

        assertThat(report.discoveredFiles()).isEqualTo(6);
        assertThat(report.readableFiles()).isEqualTo(4);
        assertThat(report.processableFiles()).isEqualTo(3);
        assertThat(report.usage()).isNull();
        assertThat(report.diagnostics()).containsExactly(
                new CodexCollectionIssue(List.of(1), ProviderCollectionFailure.INVALID_JSON),
                new CodexCollectionIssue(List.of(2), ProviderCollectionFailure.NO_TOKEN_COUNT),
                new CodexCollectionIssue(List.of(3), ProviderCollectionFailure.UNSUPPORTED_HISTORY),
                new CodexCollectionIssue(List.of(4, 5), ProviderCollectionFailure.CONFLICTING_DATA)
        );
    }

    // Verifies that daily diagnostics reconcile with the public adapter for deduplicated parent and child histories.
    @Test
    void shouldReturnCompleteDailyResultsForValidSources() throws Exception {
        for (final var name : List.of("parent", "child", "fresh-child", "inherited-only-child")) {
            final var resource = Objects.requireNonNull(getClass().getResource("/codex/daily/" + name + ".jsonl"));
            Files.copy(Path.of(resource.toURI()), directory.resolve(name + ".jsonl"));
        }
        Files.copy(directory.resolve("child.jsonl"), directory.resolve("copy.jsonl"));

        final var report = inspector.inspectDaily(directory, period);

        assertThat(report.discoveredFiles()).isEqualTo(5);
        assertThat(report.processedFiles()).isEqualTo(5);
        assertThat(report.issues()).isEmpty();
        assertThat(report.usage()).isEqualTo(CodexAdapter.forDirectory(directory).getDailyUsage(period));
        assertThat(report.usage()).extracting((final var day) -> day.inputTokens()).containsExactly(190L, 30L);
    }

    // Verifies that equal final counters with contradictory daily histories identify both files even when another file is invalid.
    @Test
    void shouldIdentifyConflictingDailyFilesDespiteOtherFailures() throws Exception {
        final var content = header("same", "") + token(2, "2026-10-24T12:00:00Z", 100)
                + token(3, "2026-10-26T12:00:00Z", 200);
        Files.writeString(directory.resolve("a.jsonl"), content);
        Files.writeString(directory.resolve("b.jsonl"), content.replace("2026-10-24T12:00:00Z", "2026-10-25T12:00:00Z"));
        Files.writeString(directory.resolve("c.jsonl"), "not JSON");
        final var later = new UsagePeriod(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-10-26"), ZoneId.of("UTC"));

        final var report = inspector.inspectDaily(directory, later);

        assertThat(report.usage()).isNull();
        assertThat(report.processedFiles()).isEqualTo(2);
        assertThat(report.issues()).containsExactly(
                new CodexCollectionIssue(List.of(1, 2), ProviderCollectionFailure.CONFLICTING_DATA),
                new CodexCollectionIssue(List.of(3), ProviderCollectionFailure.INVALID_JSON)
        );
    }

    // Verifies that missing components remain null rather than becoming diagnostic failures or zero counts.
    @Test
    void shouldAcceptUnknownMetrics() throws Exception {
        Files.writeString(directory.resolve("session.jsonl"), header("test", "")
                + token(2, "2026-10-25T12:00:00Z", 100).replace("\"cached_input_tokens\":0", "\"cached_input_tokens\":null"));

        final var report = inspector.inspectDaily(directory, period);

        assertThat(report.issues()).isEmpty();
        assertThat(report.usage().getFirst().inputTokens()).isEqualTo(100L);
        assertThat(report.usage().getFirst().cachedTokens()).isNull();
    }

    // Verifies that directory-wide overflow is classified without blaming a single file or exposing partial totals.
    @Test
    void shouldClassifyAggregateOverflow() throws Exception {
        Files.writeString(directory.resolve("a.jsonl"), header("one", "") + token(2, "2026-10-25T12:00:00Z", Long.MAX_VALUE));
        Files.writeString(directory.resolve("b.jsonl"), header("two", "") + token(2, "2026-10-25T12:00:00Z", 1));

        final var cumulative = inspector.inspect(directory);
        final var daily = inspector.inspectDaily(directory, period);

        final var issue = new CodexCollectionIssue(List.of(), ProviderCollectionFailure.OVERFLOW);
        assertThat(cumulative.usage()).isNull();
        assertThat(cumulative.diagnostics()).containsExactly(issue);
        assertThat(daily.usage()).isNull();
        assertThat(daily.processedFiles()).isEqualTo(2);
        assertThat(daily.issues()).containsExactly(issue);
    }

    // Verifies the distinction between a successful empty result and an unavailable source.
    @Test
    void shouldDistinguishEmptyDirectoryFromDiscoveryFailure() {
        final var empty = inspector.inspectDaily(directory, period);
        assertThat(empty.usage()).isEmpty();
        assertThat(empty.issues()).isEmpty();
        final var error = assertThrows(ProviderCollectionException.class,
                () -> inspector.inspectDaily(directory.resolve("missing"), period));
        assertThat(error.failure()).isEqualTo(ProviderCollectionFailure.READ_FAILURE);
    }

    private String header(final String id, final String extra) {
        return "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"history_mode\":\"paginated\"" + extra + "}}\n";
    }

    private String token(final int ordinal, final String timestamp, final long input) {
        final var counts = "{\"input_tokens\":%d,\"output_tokens\":0,\"cached_input_tokens\":0}".formatted(input);
        return """
                {"ordinal":%d,"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":%s,"last_token_usage":%s}}}
                """.formatted(ordinal, timestamp, counts, counts);
    }
}
