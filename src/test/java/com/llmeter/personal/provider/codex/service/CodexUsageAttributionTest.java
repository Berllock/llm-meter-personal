package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexUsageAttributionTest {

    private final CodexSessionReader reader = new CodexSessionReader();
    private final CodexSessionAggregator aggregator = new CodexSessionAggregator();

    // Verifies that a fresh paginated child contributes its own counters once despite repeated token snapshots.
    @Test
    void shouldAttributeFreshChildUsage() throws Exception {
        final var parent = reader.read(fixture("parent"));
        final var child = reader.read(fixture("fresh-child"));

        final var result = aggregator.aggregate(List.of(parent, child));

        assertThat(child.attributableUsage()).isNotNull();
        assertThat(child.attributableUsage().inputTokens()).isEqualTo(150L);
        assertThat(result.inputTokens()).isEqualTo(1150L);
        assertThat(result.outputTokens()).isEqualTo(115L);
        assertThat(result.cachedTokens()).isEqualTo(920L);
    }

    // Verifies that a visible inherited baseline is subtracted only after the authoritative ordinal boundary.
    @ParameterizedTest
    @ValueSource(strings = {"inherited-child", "subagent-fork"})
    void shouldSubtractInheritedPrefix(final String name) throws Exception {
        final var parent = reader.read(fixture("parent"));
        final var child = reader.read(fixture(name));

        final var result = aggregator.aggregate(List.of(parent, child));

        assertThat(child.usage().inputTokens()).isEqualTo(700L);
        assertThat(child.attributableUsage().inputTokens()).isEqualTo(100L);
        assertThat(child.attributableUsage().outputTokens()).isEqualTo(10L);
        assertThat(child.attributableUsage().cachedTokens()).isEqualTo(80L);
        assertThat(result.inputTokens()).isEqualTo(1100L);
        assertThat(result.outputTokens()).isEqualTo(110L);
        assertThat(result.cachedTokens()).isEqualTo(880L);
    }

    // Verifies that a child containing only inherited counters contributes zero rather than its parent's history.
    @Test
    void shouldExcludeInheritedUsageWhenChildHasNoOwnTokenEvents() throws Exception {
        final var parent = reader.read(fixture("parent"));
        final var child = reader.read(fixture("inherited-only-child"));

        final var result = aggregator.aggregate(List.of(parent, child));

        assertThat(child.usage().inputTokens()).isEqualTo(600L);
        assertThat(child.attributableUsage().inputTokens()).isZero();
        assertThat(result.inputTokens()).isEqualTo(1000L);
        assertThat(result.outputTokens()).isEqualTo(100L);
        assertThat(result.cachedTokens()).isEqualTo(800L);
    }

    // Verifies that copied related files are deduplicated by identity and contribution, ignoring collection time.
    @Test
    void shouldDeduplicateAttributedCopies() throws Exception {
        final var parent = reader.read(fixture("parent"));
        final var first = reader.read(fixture("inherited-child"));
        final var copy = reader.read(fixture("inherited-child"));

        final var result = aggregator.aggregate(List.of(parent, first, copy));

        assertThat(result.inputTokens()).isEqualTo(1100L);
    }

    // Verifies that legacy, referenced, reset, and incomplete histories remain unavailable for safe aggregation.
    @ParameterizedTest
    @ValueSource(strings = {
            "ambiguous-child", "external-history-child", "standalone-fork", "reset-child", "missing-last-child"
    })
    void shouldRejectUnprovenAttribution(final String name) throws Exception {
        final var child = reader.read(fixture(name));

        assertThat(child.usage()).isNotNull();
        assertThat(child.attributableUsage()).isNull();
        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(child)));
    }

    // Verifies that directory discovery, attribution, aggregation, and diagnostics agree on a complete result.
    @Test
    void shouldCollectDirectoryWithAttributedChildren(@TempDir final Path directory) throws Exception {
        Files.copy(fixture("parent"), directory.resolve("parent.jsonl"));
        Files.copy(fixture("fresh-child"), directory.resolve("fresh.jsonl"));
        Files.copy(fixture("inherited-child"), directory.resolve("inherited.jsonl"));
        Files.copy(fixture("inherited-child"), directory.resolve("copy.jsonl"));

        final var usage = CodexAdapter.forDirectory(directory).getUsage();
        final var report = new CodexDirectoryInspector().inspect(directory);

        assertThat(usage.inputTokens()).isEqualTo(1250L);
        assertThat(usage.outputTokens()).isEqualTo(125L);
        assertThat(usage.cachedTokens()).isEqualTo(1000L);
        assertThat(report.relatedSessions()).isEqualTo(3);
        assertThat(report.attributedRelatedSessions()).isEqualTo(3);
        assertThat(report.issues()).isEmpty();
        assertThat(report.usage().inputTokens()).isEqualTo(1250L);
    }

    // Verifies that a missing inherited metric stays unknown instead of subtracting an invented zero baseline.
    @Test
    void shouldPreserveUnknownInheritedMetrics(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("partial.jsonl");
        Files.writeString(file, Files.readString(fixture("inherited-child"))
                .replace("\"cached_input_tokens\":400", "\"cached_input_tokens\":null"));

        final var child = reader.read(file);
        final var result = aggregator.aggregate(List.of(child));

        assertThat(result.inputTokens()).isEqualTo(100L);
        assertThat(result.outputTokens()).isEqualTo(10L);
        assertThat(result.cachedTokens()).isNull();
    }

    // Verifies that metadata introduced after token events cannot retroactively prove ownership of those counters.
    @Test
    void shouldRejectLateMetadataForAttribution(@TempDir final Path directory) throws Exception {
        final var lines = Files.readAllLines(fixture("fresh-child"));
        final var file = directory.resolve("late-metadata.jsonl");
        Files.writeString(file, lines.get(1) + "\n" + lines.getFirst() + "\n" + lines.getLast() + "\n");

        final var child = reader.read(file);

        assertThat(child.attributableUsage()).isNull();
    }

    // Verifies that missing, decreasing, conflicting, and out-of-range ordinals cannot prove a child history boundary.
    @ParameterizedTest
    @ValueSource(strings = {
            "\"ordinal\":null", "\"ordinal\":-1", "\"ordinal\":1.5",
            "\"ordinal\":9223372036854775808", "\"ordinal\":2"
    })
    void shouldRejectInvalidOrConflictingOrdinals(final String ordinal, @TempDir final Path directory)
            throws Exception {
        final var file = directory.resolve("bad-ordinal.jsonl");
        Files.writeString(file, Files.readString(fixture("fresh-child")).replace("\"ordinal\":4", ordinal));

        final var child = reader.read(file);

        assertThat(child.attributableUsage()).isNull();
    }

    // Verifies that a hidden initial baseline cannot be accepted merely because a parent identifier is present.
    @Test
    void shouldRejectNonzeroUnmarkedBaseline(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("hidden-baseline.jsonl");
        Files.writeString(file, Files.readString(fixture("fresh-child"))
                .replace("\"last_token_usage\":{\"input_tokens\":100", "\"last_token_usage\":{\"input_tokens\":50"));

        assertThat(reader.read(file).attributableUsage()).isNull();
    }

    // Verifies that an attributed copy cannot silently replace a different inherited baseline for the same identity.
    @Test
    void shouldRejectConflictingAttributedCopies() throws Exception {
        final var child = reader.read(fixture("inherited-child"));
        final var conflicting = new CodexSessionUsage(
                child.sessionId(), child.parentThreadId(), child.forkedFromId(), child.usage(),
                reader.read(fixture("fresh-child")).attributableUsage()
        );

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(child, conflicting)));
    }

    // Verifies that a metric missing in the middle of an owned history cannot later reappear as a complete total.
    @Test
    void shouldPreserveUnknownIntermediateMetrics(@TempDir final Path directory) throws Exception {
        final var lines = Files.readAllLines(fixture("fresh-child"));
        lines.set(2, lines.get(2).replace("\"cached_input_tokens\":80", "\"cached_input_tokens\":null"));
        final var file = Files.write(directory.resolve("missing-intermediate.jsonl"), lines);

        final var usage = reader.read(file).attributableUsage();

        assertThat(usage.inputTokens()).isEqualTo(150L);
        assertThat(usage.outputTokens()).isEqualTo(15L);
        assertThat(usage.cachedTokens()).isNull();
    }

    // Verifies that an identical ordinal replay does not add a second charge or invalidate an otherwise proven stream.
    @Test
    void shouldIgnoreExactOrdinalReplay(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("replay.jsonl");
        Files.writeString(file, Files.readString(fixture("fresh-child")).replace("\"ordinal\":3", "\"ordinal\":2"));

        assertThat(reader.read(file).attributableUsage().inputTokens()).isEqualTo(150L);
    }

    // Verifies that malformed history boundaries never become an implicit fresh-child declaration.
    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "9223372036854775808", "\"10\""})
    void shouldRejectInvalidHistoryBoundaries(final String boundary, @TempDir final Path directory)
            throws Exception {
        final var file = directory.resolve("invalid-boundary.jsonl");
        Files.writeString(file, Files.readString(fixture("inherited-child"))
                .replace("\"subagent_history_start_ordinal\":10", "\"subagent_history_start_ordinal\":" + boundary));

        assertThat(reader.read(file).attributableUsage()).isNull();
    }

    // Verifies that replayed headers remain unsupported until their ownership semantics can be established.
    @Test
    void shouldRejectRepeatedMetadataForAttribution(@TempDir final Path directory) throws Exception {
        final var lines = Files.readAllLines(fixture("fresh-child"));
        lines.add(2, lines.getFirst());
        final var file = Files.write(directory.resolve("repeated-metadata.jsonl"), lines);

        assertThat(reader.read(file).attributableUsage()).isNull();
    }

    // Verifies the documented nested parent metadata form without confusing it with a standalone root session.
    @Test
    void shouldReadNestedParentIdentity(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("nested-parent.jsonl");
        Files.writeString(file, Files.readString(fixture("fresh-child"))
                .replace("\"parent_thread_id\":\"parent\"",
                        "\"source\":{\"subagent\":{\"thread_spawn\":{\"parent_thread_id\":\"parent\"}}}"));

        final var child = reader.read(file);

        assertThat(child.parentThreadId()).isEqualTo("parent");
        assertThat(child.attributableUsage().inputTokens()).isEqualTo(150L);
    }

    // Verifies that contradictory parent declarations fail rather than assigning counters to the wrong history.
    @Test
    void shouldRejectConflictingNestedParentIdentity(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("conflicting-parent.jsonl");
        Files.writeString(file, Files.readString(fixture("fresh-child"))
                .replace("\"parent_thread_id\":\"parent\"",
                        "\"parent_thread_id\":\"parent\",\"source\":{\"subagent\":{\"thread_spawn\":{\"parent_thread_id\":\"other\"}}}"));

        assertThrows(ProviderCollectionException.class, () -> reader.read(file));
    }

    private Path fixture(final String name) throws Exception {
        return Path.of(Objects.requireNonNull(
                getClass().getResource("/codex/attribution/" + name + ".jsonl"), "Test resource not found"
        ).toURI());
    }
}
