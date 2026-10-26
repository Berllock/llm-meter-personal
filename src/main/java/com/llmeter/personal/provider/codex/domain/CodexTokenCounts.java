package com.llmeter.personal.provider.codex.domain;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.time.Instant;

public record CodexTokenCounts(Long input, Long output, Long cached) {

    public UsageSnapshot snapshot(final Instant collectedAt) {
        return new UsageSnapshot(
                ProviderType.CODEX, input, output, cached, collectedAt
        );
    }

    public CodexTokenCounts zeroForKnownMetrics() {
        return new CodexTokenCounts(input == null ? null : 0L,
                output == null ? null : 0L, cached == null ? null : 0L);
    }

    public CodexTokenCounts retainKnownMetrics(final CodexTokenCounts other) {
        return new CodexTokenCounts(other.input == null ? null : input,
                other.output == null ? null : output, other.cached == null ? null : cached);
    }

    public boolean hasSameKnownMetrics(final CodexTokenCounts other) {
        return matches(input, other.input) && matches(output, other.output) && matches(cached, other.cached);
    }

    public boolean decreasesFrom(final CodexTokenCounts previous) {
        return decreases(input, previous.input) || decreases(output, previous.output)
                || decreases(cached, previous.cached);
    }

    public CodexTokenCounts subtract(final CodexTokenCounts baseline) {
        return new CodexTokenCounts(difference(input, baseline.input),
                difference(output, baseline.output), difference(cached, baseline.cached));
    }

    public CodexTokenCounts add(final CodexTokenCounts other) {
        try {
            return new CodexTokenCounts(sum(input, other.input), sum(output, other.output), sum(cached, other.cached));
        } catch (final ArithmeticException e) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.OVERFLOW, "Codex daily token total exceeds long range", e
            );
        }
    }

    public CodexTokenCounts latestKnown(final CodexTokenCounts other) {
        return new CodexTokenCounts(other.input == null ? input : other.input,
                other.output == null ? output : other.output, other.cached == null ? cached : other.cached);
    }

    private static Long sum(final Long first, final Long second) {
        return first == null || second == null ? null : Long.valueOf(Math.addExact(first, second));
    }

    private static boolean matches(final Long total, final Long last) {
        return total == null || total.equals(last);
    }

    private static boolean decreases(final Long current, final Long previous) {
        return current != null && previous != null && current < previous;
    }

    private static Long difference(final Long total, final Long baseline) {
        return total == null || baseline == null ? null : Long.valueOf(total - baseline);
    }

}
