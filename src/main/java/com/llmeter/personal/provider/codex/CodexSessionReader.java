package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.ProviderCollectionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class CodexSessionReader {

    private final JsonMapper mapper = new JsonMapper();

    public CodexSessionUsage read(final Path sessionFile) {

        try (final var reader = Files.newBufferedReader(sessionFile)) {

            String sessionId = null;
            String parentThreadId = null;
            String forkedFromId = null;
            JsonNode lastTokenUsage = null;
            CodexUsageAttributor attributor = null;
            String line;
            long lineNumber = 0;

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
                            "Invalid JSON in Codex session at line " + lineNumber, e
                    );
                }
                final var payload = event.path("payload");

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
                    continue;
                }

                if (!event.path("type").equals(mapper.valueToTree("event_msg"))
                        || !payload.path("type").equals(mapper.valueToTree("token_count"))) {
                    continue;
                }

                final var tokenUsage = payload
                        .path("info")
                        .path("total_token_usage");

                if (tokenUsage.isObject()) {
                    lastTokenUsage = tokenUsage;
                    if (attributor != null) {
                        attributor.observe(event, payload.path("info"));
                    }
                }
            }

            if (lastTokenUsage == null) {
                throw new ProviderCollectionException("No token count found in session file");
            }

            final var collectedAt = Instant.now();
            final var usage = CodexTokenCounts.read(lastTokenUsage).snapshot(collectedAt);
            final var attributableUsage = attributor == null ? null : attributor.attributedUsage(collectedAt);
            return new CodexSessionUsage(sessionId, parentThreadId, forkedFromId, usage, attributableUsage);
        } catch (final IOException e) {
            throw new ProviderCollectionException("Could not read the Codex session", e);
        }

    }

    private String mergeIdentifier(final String previous, final String candidate, final String field) {
        if (previous != null && candidate != null && !previous.equals(candidate)) {
            throw new ProviderCollectionException("Conflicting session identifiers for field " + field);
        }
        return candidate != null ? candidate : previous;
    }

    private String readIdentifier(final JsonNode metadata, final String field) {
        final var value = metadata.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new ProviderCollectionException("Invalid session identifier for field " + field);
        }
        return value.stringValue();
    }

}
