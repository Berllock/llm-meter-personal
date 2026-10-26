package com.llmeter.personal.provider.codex;

import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

final class CodexUsageAttributor {

    private final Long boundary;
    private final boolean fork;
    private boolean supported;
    private Long previousOrdinal;
    private CodexTokenCounts previous;
    private CodexTokenCounts baseline;
    private CodexTokenCounts knownMetrics;

    CodexUsageAttributor(final JsonNode metadata, final boolean hasParent, final boolean hasFork) {
        final var mode = metadata.path("history_mode");
        final var start = metadata.path("subagent_history_start_ordinal");
        final boolean hasBoundary = !start.isMissingNode() && !start.isNull();
        final boolean validBoundary = !hasBoundary
                || (start.isIntegralNumber() && start.canConvertToLong() && start.longValue() >= 0);
        boundary = hasBoundary && validBoundary ? Long.valueOf(start.longValue()) : null;
        fork = hasFork;
        supported = mode.isString() && "paginated".equals(mode.stringValue())
                && hasParent && !metadata.hasNonNull("history_base") && validBoundary
                && (!hasFork || boundary != null);
    }

    void invalidate() {
        supported = false;
    }

    void observe(final JsonNode event, final JsonNode info) {
        if (!supported) {
            return;
        }
        final var ordinalNode = event.path("ordinal");
        if (!ordinalNode.isIntegralNumber() || !ordinalNode.canConvertToLong() || ordinalNode.longValue() < 0) {
            invalidate();
            return;
        }
        final long ordinal = ordinalNode.longValue();
        final var total = CodexTokenCounts.read(info.path("total_token_usage"));
        if (previousOrdinal != null && ordinal <= previousOrdinal) {
            if (ordinal == previousOrdinal && total.equals(previous)) {
                return;
            }
            invalidate();
            return;
        }

        if (boundary != null && ordinal < boundary) {
            baseline = total;
            knownMetrics = total;
        } else {
            if (baseline == null) {
                // A fresh paginated child must start from zero, proven by its first request counters.
                final var lastNode = info.path("last_token_usage");
                if (fork || !lastNode.isObject()
                        || !total.hasSameKnownMetrics(CodexTokenCounts.read(lastNode))) {
                    invalidate();
                    return;
                }
                baseline = total.zeroForKnownMetrics();
                knownMetrics = baseline;
            }
            if (total.decreasesFrom(previous != null ? previous : baseline)) {
                invalidate();
                return;
            }
            knownMetrics = knownMetrics.retainKnownMetrics(total);
        }
        previous = total;
        previousOrdinal = ordinal;
    }

    UsageSnapshot attributedUsage(final Instant collectedAt) {
        if (!supported || baseline == null || previous == null) {
            return null;
        }
        return previous.subtract(baseline).retainKnownMetrics(knownMetrics).snapshot(collectedAt);
    }
}
