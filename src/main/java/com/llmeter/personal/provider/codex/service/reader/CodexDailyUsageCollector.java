package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import tools.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;

/** Collects observed daily increments without retaining the session transcript. */
final class CodexDailyUsageCollector {

    private final ZoneId zone;
    private final Map<LocalDate, CodexTokenCounts> days = new TreeMap<>();
    private boolean started;
    private boolean related;
    private Long boundary;
    private Long previousOrdinal;
    private Instant previousTime;
    private CodexTokenCounts previousRaw;
    private CodexTokenCounts previousContribution;
    private CodexTokenCounts lastKnown = new CodexTokenCounts(null, null, null);

    CodexDailyUsageCollector(final ZoneId zone) {
        this.zone = zone;
    }

    void start(final JsonNode metadata, final boolean hasParent, final boolean hasFork) {
        final var mode = metadata.path("history_mode");
        if (started || !mode.isString() || !"paginated".equals(mode.stringValue())
                || metadata.hasNonNull("history_base")) {
            throw unavailable();
        }
        started = true;
        related = hasParent || hasFork;
        final var start = metadata.path("subagent_history_start_ordinal");
        if (start.isIntegralNumber() && start.canConvertToLong() && start.longValue() >= 0) {
            boundary = start.longValue();
        } else if (!start.isMissingNode() && !start.isNull()) {
            throw unavailable();
        }
        if (!related && boundary != null) {
            throw unavailable();
        }
    }

    void observe(final JsonNode event, final JsonNode info, final CodexUsageAttributor attributor) {
        if (!started) {
            throw unavailable();
        }
        final var ordinalNode = event.path("ordinal");
        if (!ordinalNode.isIntegralNumber() || !ordinalNode.canConvertToLong() || ordinalNode.longValue() < 0) {
            throw unavailable();
        }
        final long ordinal = ordinalNode.longValue();
        final var total = CodexTokenCountsReader.read(info.path("total_token_usage"));
        final CodexTokenCounts contribution;
        if (related) {
            final var attributed = attributor.attributedUsage(Instant.EPOCH);
            if (attributed == null) {
                throw unavailable();
            }
            contribution = new CodexTokenCounts(attributed.inputTokens(), attributed.outputTokens(),
                    attributed.cachedTokens());
            if (boundary != null && ordinal < boundary) {
                // Inherited history establishes the baseline, never a day of child consumption.
                previousContribution = contribution;
                previousRaw = total;
                lastKnown = total;
                previousOrdinal = ordinal;
                return;
            }
        } else {
            contribution = total;
        }

        final var timestamp = readTimestamp(event);
        if (previousOrdinal != null && ordinal <= previousOrdinal) {
            if (ordinal == previousOrdinal && total.equals(previousRaw) && timestamp.equals(previousTime)) {
                return;
            }
            throw unavailable();
        }
        if (previousTime != null && timestamp.isBefore(previousTime)) {
            throw unavailable();
        }
        if (previousContribution == null) {
            if (!related && (!info.path("last_token_usage").isObject()
                    || !total.hasSameKnownMetrics(CodexTokenCountsReader.read(info.path("last_token_usage"))))) {
                throw unavailable();
            }
            previousContribution = contribution.zeroForKnownMetrics();
        }
        if (total.decreasesFrom(lastKnown)) {
            throw unavailable();
        }
        // Once a component disappears its subsequent timing cannot be reconstructed reliably.
        final var knownContribution = contribution.retainKnownMetrics(previousContribution);
        final var delta = knownContribution.subtract(previousContribution);
        if (!delta.equals(new CodexTokenCounts(0L, 0L, 0L))) {
            days.merge(timestamp.atZone(zone).toLocalDate(), delta, CodexTokenCounts::add);
        }
        previousContribution = knownContribution;
        previousRaw = total;
        lastKnown = lastKnown.latestKnown(total);
        previousOrdinal = ordinal;
        previousTime = timestamp;
    }

    Map<LocalDate, CodexTokenCounts> days() {
        return Map.copyOf(days);
    }

    private Instant readTimestamp(final JsonNode event) {
        final var timestamp = event.path("timestamp");
        if (!timestamp.isString()) {
            throw unavailable();
        }
        try {
            return Instant.parse(timestamp.stringValue());
        } catch (final DateTimeException e) {
            throw new ProviderCollectionException("Invalid Codex token event timestamp", e);
        }
    }

    private ProviderCollectionException unavailable() {
        return new ProviderCollectionException("Codex history cannot be attributed to daily usage safely");
    }
}
