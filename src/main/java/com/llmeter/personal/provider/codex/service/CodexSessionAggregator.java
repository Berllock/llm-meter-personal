package com.llmeter.personal.provider.codex.service;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class CodexSessionAggregator {

    public UsageSnapshot aggregate(final List<CodexSessionUsage> sessions) {
        final Map<String, CodexSessionUsage> uniqueSessions = new LinkedHashMap<>();
        for (final var session : sessions) {
            if (session.sessionId() == null || session.sessionId().isBlank()) {
                throw new ProviderCollectionException("Cannot aggregate a session without an identifier");
            }
            if (session.usage().provider() != ProviderType.CODEX) {
                throw new ProviderCollectionException("Cannot aggregate usage from another provider as Codex");
            }
            final var collectible = collectibleUsage(session);
            if (collectible.provider() != ProviderType.CODEX) {
                throw new ProviderCollectionException("Cannot aggregate usage from another provider as Codex");
            }

            final var previous = uniqueSessions.putIfAbsent(session.sessionId(), session);
            if (previous != null && (!hasSameMetrics(previous.usage(), session.usage())
                    || !hasSameMetrics(collectibleUsage(previous), collectible)
                    || !Objects.equals(previous.parentThreadId(), session.parentThreadId())
                    || !Objects.equals(previous.forkedFromId(), session.forkedFromId()))) {
                throw new ProviderCollectionException("Conflicting usage for the same Codex session");
            }
        }

        final var unique = uniqueSessions.values();
        return new UsageSnapshot(
                ProviderType.CODEX,
                sumTokens(unique, UsageSnapshot::inputTokens, "input_tokens"),
                sumTokens(unique, UsageSnapshot::outputTokens, "output_tokens"),
                sumTokens(unique, UsageSnapshot::cachedTokens, "cached_input_tokens"),
                Instant.now()
        );
    }

    private UsageSnapshot collectibleUsage(final CodexSessionUsage session) {
        if (session.parentThreadId() == null && session.forkedFromId() == null) {
            return session.usage();
        }
        if (session.attributableUsage() == null) {
            throw new ProviderCollectionException(
                    "Related Codex sessions require inherited usage attribution before aggregation"
            );
        }
        return session.attributableUsage();
    }

    private boolean hasSameMetrics(final UsageSnapshot first, final UsageSnapshot second) {
        return Objects.equals(first.inputTokens(), second.inputTokens())
                && Objects.equals(first.outputTokens(), second.outputTokens())
                && Objects.equals(first.cachedTokens(), second.cachedTokens());
    }

    private Long sumTokens(
            final Collection<CodexSessionUsage> sessions,
            final Function<UsageSnapshot, Long> metric,
            final String field
    ) {
        long total = 0;
        boolean complete = true;
        for (final var session : sessions) {
            final var tokens = metric.apply(collectibleUsage(session));
            if (tokens == null) {
                complete = false;
                continue;
            }
            if (tokens < 0) {
                throw new ProviderCollectionException("Negative token count for field " + field);
            }
            try {
                total = Math.addExact(total, tokens);
            } catch (final ArithmeticException e) {
                throw new ProviderCollectionException("Codex token total exceeds long range for field " + field, e);
            }
        }
        return complete ? Long.valueOf(total) : null;
    }
}
