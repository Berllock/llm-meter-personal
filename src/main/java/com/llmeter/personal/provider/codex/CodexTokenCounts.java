package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

record CodexTokenCounts(Long input, Long output, Long cached) {

    static CodexTokenCounts read(final JsonNode node) {
        return new CodexTokenCounts(
                readToken(node, "input_tokens"),
                readToken(node, "output_tokens"),
                readToken(node, "cached_input_tokens")
        );
    }

    UsageSnapshot snapshot(final Instant collectedAt) {
        return new UsageSnapshot(
                ProviderType.CODEX, input, output, cached, collectedAt
        );
    }

    CodexTokenCounts zeroForKnownMetrics() {
        return new CodexTokenCounts(input == null ? null : 0L,
                output == null ? null : 0L, cached == null ? null : 0L);
    }

    CodexTokenCounts retainKnownMetrics(final CodexTokenCounts other) {
        return new CodexTokenCounts(other.input == null ? null : input,
                other.output == null ? null : output, other.cached == null ? null : cached);
    }

    boolean hasSameKnownMetrics(final CodexTokenCounts other) {
        return matches(input, other.input) && matches(output, other.output) && matches(cached, other.cached);
    }

    boolean decreasesFrom(final CodexTokenCounts previous) {
        return decreases(input, previous.input) || decreases(output, previous.output)
                || decreases(cached, previous.cached);
    }

    CodexTokenCounts subtract(final CodexTokenCounts baseline) {
        return new CodexTokenCounts(difference(input, baseline.input),
                difference(output, baseline.output), difference(cached, baseline.cached));
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

    private static Long readToken(final JsonNode node, final String field) {
        final var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProviderCollectionException("Invalid token count for field " + field);
        }
        final var tokens = value.longValue();
        if (tokens < 0) {
            throw new ProviderCollectionException("Negative token count for field " + field);
        }
        return tokens;
    }
}
