package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.codex.domain.CodexSessionUsage;
import com.llmeter.personal.provider.codex.dto.CodexDailySessionUsage;
import com.llmeter.personal.provider.codex.dto.CodexDailyObservation;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

public class CodexSessionReader {

    private final JsonMapper mapper = new JsonMapper();

    public CodexSessionUsage read(final Path sessionFile) {
        return read(sessionFile, null);
    }

    public CodexDailySessionUsage readDaily(final Path sessionFile, final ZoneId zone) {
        final var collector = new CodexDailyUsageCollector(zone);
        final var session = read(sessionFile, collector);
        return new CodexDailySessionUsage(session, collector.days());
    }

    public CodexDailyObservation readDailyObservation(final Path sessionFile, final ZoneId zone) {
        final var daily = new CodexDailyUsageCollector(zone);
        final var result = readSession(sessionFile, daily, true);
        return new CodexDailyObservation(result.sessionId(), result.parentThreadId(), result.forkedFromId(),
                result.session() == null ? null : new CodexDailySessionUsage(result.session(), daily.days()));
    }

    private CodexSessionUsage read(final Path sessionFile, final CodexDailyUsageCollector daily) {
        return readSession(sessionFile, daily, false).session();
    }

    private record SessionRead(String sessionId, String parentThreadId, String forkedFromId, CodexSessionUsage session) {
    }

    private SessionRead readSession(final Path sessionFile, final CodexDailyUsageCollector daily, final boolean allowPending) {

        try (final var reader = Files.newBufferedReader(sessionFile)) {

            String sessionId = null;
            String parentThreadId = null;
            String forkedFromId = null;
            JsonNode lastTokenUsage = null;
            CodexUsageAttributor attributor = null;
            String line;
            long lineNumber = 0;
            long eventNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                final JsonNode event;
                try {
                    event = mapper.readTree(line);
                } catch (final JacksonException e) {
                    throw new ProviderCollectionException(
                            ProviderCollectionFailure.INVALID_JSON, "Invalid JSON in Codex session at line " + lineNumber, e
                    );
                }
                eventNumber++;
                final var payload = event.path("payload");
                if (allowPending && eventNumber == 1 && (!"session_meta".equals(event.path("type").asString())
                        || !event.path("ordinal").isIntegralNumber() || !event.path("ordinal").canConvertToLong()
                        || event.path("ordinal").longValue() != 0)) {
                    throw new ProviderCollectionException(ProviderCollectionFailure.INVALID_SEQUENCE,
                            "A leading session header is required for coverage");
                }

                if (event.path("type").equals(mapper.valueToTree("session_meta"))) {
                    sessionId = mergeIdentifier(sessionId, readIdentifier(payload, "id"), "id");
                    parentThreadId = mergeIdentifier(
                            parentThreadId, readIdentifier(payload, "parent_thread_id"), "parent_thread_id"
                    );
                    parentThreadId = mergeIdentifier(
                            parentThreadId,
                            readIdentifier(payload.path("source").path("subagent").path("thread_spawn"),
                                    "parent_thread_id"),
                            "parent_thread_id"
                    );
                    forkedFromId = mergeIdentifier(
                            forkedFromId, readIdentifier(payload, "forked_from_id"), "forked_from_id"
                    );
                    if (attributor == null) {
                        attributor = new CodexUsageAttributor(payload, parentThreadId != null, forkedFromId != null);
                        if (lastTokenUsage != null) {
                            attributor.invalidate();
                        }
                    } else {
                        attributor.invalidate();
                    }
                    if (allowPending && forkedFromId != null && (parentThreadId == null
                            || !payload.hasNonNull("subagent_history_start_ordinal"))) {
                        throw new ProviderCollectionException(ProviderCollectionFailure.UNSUPPORTED_HISTORY,
                                "Unsupported fork history");
                    }
                    if (daily != null) {
                        daily.start(payload, parentThreadId != null, forkedFromId != null);
                    }
                    continue;
                }

                if (!event.path("type").equals(mapper.valueToTree("event_msg"))
                        || !payload.path("type").equals(mapper.valueToTree("token_count"))) {
                    continue;
                }

                final var tokenUsage = payload
                        .path("info")
                        .path("total_token_usage");

                if (allowPending && !tokenUsage.isObject()) {
                    throw new ProviderCollectionException(ProviderCollectionFailure.INVALID_METRICS,
                            "A token event must contain valid counters");
                }
                if (tokenUsage.isObject()) {
                    lastTokenUsage = tokenUsage;
                    if (attributor != null) {
                        attributor.observe(event, payload.path("info"));
                    }
                    if (daily != null) {
                        daily.observe(event, payload.path("info"), attributor);
                    }
                }
            }

            if (allowPending && sessionId == null) {
                throw new ProviderCollectionException(ProviderCollectionFailure.INVALID_IDENTITY,
                        "A session identity is required for coverage");
            }
            if (lastTokenUsage == null && allowPending) {
                return new SessionRead(sessionId, parentThreadId, forkedFromId, null);
            }
            if (lastTokenUsage == null) {
                throw new ProviderCollectionException(
                        ProviderCollectionFailure.NO_TOKEN_COUNT, "No token count found in session file"
                );
            }

            final var collectedAt = Instant.now();
            final var usage = CodexTokenCountsReader.read(lastTokenUsage).snapshot(collectedAt);
            final var attributableUsage = attributor == null ? null : attributor.attributedUsage(collectedAt);
            return new SessionRead(sessionId, parentThreadId, forkedFromId,
                    new CodexSessionUsage(sessionId, parentThreadId, forkedFromId, usage, attributableUsage));
        } catch (final IOException e) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.READ_FAILURE, "Could not read the Codex session", e
            );
        }

    }

    private String mergeIdentifier(final String previous, final String candidate, final String field) {
        if (previous != null && candidate != null && !previous.equals(candidate)) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.CONFLICTING_DATA, "Conflicting session identifiers for field " + field
            );
        }
        return candidate != null ? candidate : previous;
    }

    private String readIdentifier(final JsonNode metadata, final String field) {
        final var value = metadata.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.INVALID_IDENTITY, "Invalid session identifier for field " + field
            );
        }
        return value.stringValue();
    }

}
