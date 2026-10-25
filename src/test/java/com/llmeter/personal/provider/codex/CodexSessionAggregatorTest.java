package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSessionAggregatorTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-10-22T12:00:00Z");

    // Verifies that independent session counters are summed and cached tokens remain a separate metric.
    @Test
    void shouldSumIndependentSessions() {
        final var first = session("session-a", 100L, 40L, 20L);
        final var second = session("session-b", 200L, 60L, 30L);
        final var aggregator = new CodexSessionAggregator();
        final var before = Instant.now();

        final var result = aggregator.aggregate(List.of(first, second));

        assertEquals(ProviderType.CODEX, result.provider());
        assertEquals(Long.valueOf(300L), result.inputTokens());
        assertEquals(Long.valueOf(100L), result.outputTokens());
        assertEquals(Long.valueOf(50L), result.cachedTokens());
        assertTrue(!result.collectedAt().isBefore(before) && !result.collectedAt().isAfter(Instant.now()));
    }

    // Verifies that copies of one session count once even when our collection timestamps differ.
    @Test
    void shouldDeduplicateIdenticalSessionsIgnoringCollectionTime() {
        final var first = session("session-a", 100L, 40L, 20L);
        final var copy = new CodexSessionUsage("session-a", null, null, new UsageSnapshot(
                ProviderType.CODEX, 100L, 40L, 20L, COLLECTED_AT.plusSeconds(60)
        ));
        final var other = session("session-b", 100L, 40L, 20L);
        final var aggregator = new CodexSessionAggregator();

        final var result = aggregator.aggregate(List.of(first, copy, other));

        assertEquals(Long.valueOf(200L), result.inputTokens());
        assertEquals(Long.valueOf(80L), result.outputTokens());
        assertEquals(Long.valueOf(40L), result.cachedTokens());
    }

    // Verifies that conflicting copies fail instead of choosing totals based on file order or collection time.
    @ParameterizedTest
    @CsvSource({"101,40,20", "100,41,20", "100,40,21"})
    void shouldRejectConflictingSessionCopies(final long input, final long output, final long cached) {
        final var first = session("session-a", 100L, 40L, 20L);
        final var conflict = session("session-a", input, output, cached);
        final var aggregator = new CodexSessionAggregator();

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(first, conflict)));
        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(conflict, first)));
    }

    // Verifies that an unavailable metric makes only its own aggregate unavailable rather than reporting a partial sum.
    @ParameterizedTest
    @CsvSource(value = {
            "NULL,40,20,NULL,45,23",
            "100,NULL,20,110,NULL,23",
            "100,40,NULL,110,45,NULL",
            "NULL,NULL,NULL,NULL,NULL,NULL"
    }, nullValues = "NULL")
    void shouldPreserveUnavailableMetrics(
            final Long input, final Long output, final Long cached,
            final Long expectedInput, final Long expectedOutput, final Long expectedCached
    ) {
        final var first = session("session-a", input, output, cached);
        final var second = session("session-b", 10L, 5L, 3L);
        final var aggregator = new CodexSessionAggregator();

        final var result = aggregator.aggregate(List.of(first, second));

        assertEquals(expectedInput, result.inputTokens());
        assertEquals(expectedOutput, result.outputTokens());
        assertEquals(expectedCached, result.cachedTokens());
    }

    // Verifies that token totals cannot wrap around when the sum exceeds the long range.
    @ParameterizedTest
    @CsvSource({"9223372036854775807,0,0", "0,9223372036854775807,0", "0,0,9223372036854775807"})
    void shouldRejectOverflow(final long input, final long output, final long cached) {
        final var first = session("session-a", input, output, cached);
        final var second = session("session-b", 1L, 1L, 1L);
        final var aggregator = new CodexSessionAggregator();

        final var exception = assertThrows(
                ProviderCollectionException.class, () -> aggregator.aggregate(List.of(first, second))
        );

        assertInstanceOf(ArithmeticException.class, exception.getCause());
    }

    // Verifies that manually constructed negative counters cannot reduce the aggregate.
    @ParameterizedTest
    @CsvSource({"-1,0,0", "0,-1,0", "0,0,-1"})
    void shouldRejectNegativeCounts(final long input, final long output, final long cached) {
        final var session = session("session-a", input, output, cached);
        final var aggregator = new CodexSessionAggregator();

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(session)));
    }

    // Verifies that sessions without an identity cannot bypass deduplication.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRejectMissingSessionIdentity(final String sessionId) {
        final var session = session(sessionId, 100L, 40L, 20L);
        final var aggregator = new CodexSessionAggregator();

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(session)));
    }

    // Verifies that parent and fork relations are rejected until inherited usage can be attributed explicitly.
    @ParameterizedTest
    @CsvSource(value = {"parent-thread,NULL", "NULL,origin-thread"}, nullValues = "NULL")
    void shouldRejectRelatedSessions(final String parentThreadId, final String forkedFromId) {
        final var related = new CodexSessionUsage(
                "child", parentThreadId, forkedFromId, session("child", 100L, 40L, 20L).usage()
        );
        final var aggregator = new CodexSessionAggregator();

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(related)));
    }

    // Verifies that an empty set of sessions produces zero totals for that set.
    @Test
    void shouldReturnZeroTotalsForEmptyInput() {
        final var aggregator = new CodexSessionAggregator();

        final var result = aggregator.aggregate(List.of());

        assertEquals(Long.valueOf(0L), result.inputTokens());
        assertEquals(Long.valueOf(0L), result.outputTokens());
        assertEquals(Long.valueOf(0L), result.cachedTokens());
    }

    // Verifies that a foreign provider cannot be counted as Codex usage.
    @Test
    void shouldRejectUsageFromAnotherProvider() {
        final var session = new CodexSessionUsage("foreign", null, null, new UsageSnapshot(
                ProviderType.CLAUDE, 100L, 40L, 20L, COLLECTED_AT
        ));
        final var aggregator = new CodexSessionAggregator();

        assertThrows(ProviderCollectionException.class, () -> aggregator.aggregate(List.of(session)));
    }

    private CodexSessionUsage session(
            final String id, final Long input, final Long output, final Long cached
    ) {
        return new CodexSessionUsage(id, null, null, new UsageSnapshot(
                ProviderType.CODEX, input, output, cached, COLLECTED_AT
        ));
    }
}
