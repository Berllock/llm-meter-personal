package com.llmeter.personal.provider.antigravity.domain;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;

/** Canonical input includes cached reads; Antigravity's raw input excludes them. */
public record AntigravityTokenCounts(Long input, Long output, Long cached) {
    public static AntigravityTokenCounts fromRaw(final Long input, final Long output, final Long cached) {
        return new AntigravityTokenCounts(sum(input, cached), output, cached);
    }

    public AntigravityTokenCounts add(final AntigravityTokenCounts other) {
        return new AntigravityTokenCounts(sum(input, other.input), sum(output, other.output), sum(cached, other.cached));
    }

    private static Long sum(final Long first, final Long second) {
        try {
            return first == null || second == null ? null : Long.valueOf(Math.addExact(first, second));
        } catch (final ArithmeticException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.OVERFLOW,
                    "Antigravity token total exceeds long range", e);
        }
    }
}
