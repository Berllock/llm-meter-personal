package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexDailyCoverageCollectorTest {
    @TempDir Path directory;
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-30"), ZoneId.of("UTC"));

    private Path root(final String name, final String id) throws Exception {
        final var fixture = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI());
        return Files.writeString(directory.resolve(name), Files.readString(fixture).replace("\"parent\"", "\"" + id + "\""));
    }
    private Path pending(final String name, final String id) throws Exception {
        return Files.writeString(directory.resolve(name), "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"history_mode\":\"paginated\"}}\n");
    }
    private com.llmeter.personal.usage.dto.ProviderDailyUsage collect(final Path... paths) {
        return new CodexDailyCoverageCollector().collect(List.of(paths), period);
    }

    // Validates pending sessions do not erase known observations or become zero-valued rows.
    @Test void shouldReturnObservedUsageWithPendingCoverage() throws Exception {
        final var report = collect(root("observed.jsonl", "root"), pending("pending.jsonl", "pending"));
        assertThat(report.coverage().observedSessions()).isEqualTo(1);
        assertThat(report.coverage().pendingSessions()).isEqualTo(1);
        assertThat(report.usage()).hasSize(1);
        assertThat(report.usage().getFirst().inputTokens()).isEqualTo(160L);
    }
    // Validates an entirely pending source has no invented observations or zero counters.
    @Test void shouldReturnNoRowsWhenAllSessionsArePending() throws Exception {
        final var report = collect(pending("pending.jsonl", "pending"));
        assertThat(report.usage()).isEmpty();
        assertThat(report.coverage().pendingSessions()).isEqualTo(1);
        assertThat(report.coverage().observedSessions()).isZero();
    }
    // Validates repeated collections include counters appended to a previously pending session.
    @Test void shouldReconsiderPendingSessionsOnEveryCollection() throws Exception {
        final var observed = root("observed.jsonl", "root");
        final var waiting = pending("pending.jsonl", "pending");
        assertThat(collect(observed, waiting).coverage().pendingSessions()).isEqualTo(1);
        Files.writeString(waiting, """
                {"ordinal":1,"timestamp":"2026-10-27T12:00:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":50,"output_tokens":5,"cached_input_tokens":10},"last_token_usage":{"input_tokens":50,"output_tokens":5,"cached_input_tokens":10}}}}
                """, StandardOpenOption.APPEND);
        final var report = collect(observed, waiting);
        assertThat(report.coverage().pendingSessions()).isZero();
        assertThat(report.coverage().observedSessions()).isEqualTo(2);
        assertThat(report.usage().stream().mapToLong((final var row) -> row.inputTokens()).sum()).isEqualTo(210L);
    }
    // Validates source session counts deduplicate ready and pending copies by identity.
    @Test void shouldCountIdentitiesInsteadOfFiles() throws Exception {
        final var report = collect(root("one.jsonl", "root"), root("copy.jsonl", "root"),
                pending("pending.jsonl", "pending"), pending("pending-copy.jsonl", "pending"));
        assertThat(report.coverage().observedSessions()).isEqualTo(1);
        assertThat(report.coverage().pendingSessions()).isEqualTo(1);
        assertThat(report.usage().getFirst().inputTokens()).isEqualTo(160L);
    }
    // Validates coverage remains source-wide even when known observations are outside the requested dates.
    @Test void shouldNotAttributePendingSessionsToASelectedPeriod() throws Exception {
        final var outside = new UsagePeriod(LocalDate.parse("2000-01-01"), LocalDate.parse("2000-01-01"), ZoneId.of("UTC"));
        final var report = new CodexDailyCoverageCollector().collect(List.of(root("root.jsonl", "root"), pending("pending.jsonl", "pending")), outside);
        assertThat(report.usage()).isEmpty();
        assertThat(report.coverage().observedSessions()).isEqualTo(1);
        assertThat(report.coverage().pendingSessions()).isEqualTo(1);
    }
    // Validates empty, unidentified, malformed, legacy, and unsupported-fork files never masquerade as pending sessions.
    @ParameterizedTest
    @ValueSource(strings = { "", "null", "{", "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"history_mode\":\"paginated\"}}",
            "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"legacy\"}}",
            "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"fork\",\"forked_from_id\":\"parent\",\"history_mode\":\"paginated\"}}" })
    void shouldRejectInvalidPendingCandidates(final String content) throws Exception {
        final var file = Files.writeString(directory.resolve("invalid.jsonl"), content);
        assertThrows(ProviderCollectionException.class, () -> collect(file));
    }
    // Validates malformed token events are a collection failure rather than an absence of counters.
    @Test void shouldRejectMalformedCountersInsteadOfMarkingPending() throws Exception {
        final var file = pending("invalid.jsonl", "pending");
        Files.writeString(file, "{\"ordinal\":1,\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{}}}\n", StandardOpenOption.APPEND);
        assertThat(assertThrows(ProviderCollectionException.class, () -> collect(file)).failure()).isEqualTo(ProviderCollectionFailure.INVALID_METRICS);
    }
    // Validates malformed JSON after a valid pending header cannot be silently skipped.
    @Test void shouldValidateTheWholePendingFile() throws Exception {
        final var file = pending("invalid.jsonl", "pending");
        Files.writeString(file, "{", StandardOpenOption.APPEND);
        assertThat(assertThrows(ProviderCollectionException.class, () -> collect(file)).failure()).isEqualTo(ProviderCollectionFailure.INVALID_JSON);
    }
    // Validates conflicting recorded copies still fail even in the presence of pending sessions.
    @Test void shouldRejectConflictingKnownCopies() throws Exception {
        final var one = root("one.jsonl", "root");
        final var two = root("two.jsonl", "root");
        Files.writeString(two, Files.readString(two).replace("160", "170").replace("60", "70"));
        assertThat(assertThrows(ProviderCollectionException.class, () -> collect(one, two, pending("pending.jsonl", "pending"))).failure()).isEqualTo(ProviderCollectionFailure.CONFLICTING_DATA);
    }
    // Validates ambiguous pending and observed copies of one identity do not bypass copy consistency checks.
    @Test void shouldRejectMixedCounterAvailabilityForTheSameIdentity() throws Exception {
        final var one = root("one.jsonl", "same");
        final var waiting = pending("pending.jsonl", "same");
        assertThat(assertThrows(ProviderCollectionException.class, () -> collect(one, waiting)).failure()).isEqualTo(ProviderCollectionFailure.CONFLICTING_DATA);
    }
    // Validates attributed child increments and unknown metrics retain the established daily aggregation semantics.
    @Test void shouldPreserveChildAttribution() throws Exception {
        final var parent = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI());
        final var child = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/child.jsonl")).toURI());
        assertThat(collect(parent, child).usage()).isEqualTo(new CodexDailyUsageAggregator().aggregate(List.of(parent, child), period));
    }
    // Validates the legacy endpoint path still rejects missing counters instead of silently changing its array contract.
    @Test void shouldKeepLegacyDailyCollectionStrict() throws Exception {
        pending("pending.jsonl", "pending");
        assertThrows(ProviderCollectionException.class, () -> CodexAdapter.forDirectory(directory).getDailyUsage(period));
        assertThat(CodexAdapter.forDirectory(directory).getDailyUsageReport(period).coverage().pendingSessions()).isEqualTo(1);
    }
}
