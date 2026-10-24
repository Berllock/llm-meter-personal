package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public class CodexAdapter implements LlmProviderAdapter {

    private final Path sessionFile;

    private final JsonMapper mapper = new JsonMapper();

    public CodexAdapter(final Path sessionFile) {
        this.sessionFile = sessionFile;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public Optional<QuotaSnapshot> getQuota() {
        return Optional.empty();
    }

    @Override
    public UsageSnapshot getUsage() {

        try (final var reader = Files.newBufferedReader(sessionFile)) {

            JsonNode lastTokenUsage = null;
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
                    throw new IllegalStateException(
                            "Invalid JSON in Codex session at line " + lineNumber, e
                    );
                }
                final var payload = event.path("payload");

                if (!event.path("type").equals(mapper.valueToTree("event_msg"))
                        || !payload.path("type").equals(mapper.valueToTree("token_count"))) {
                    continue;
                }

                final var tokenUsage = payload
                        .path("info")
                        .path("total_token_usage");

                if (tokenUsage.isObject()) {
                    lastTokenUsage = tokenUsage;
                }
            }

            if (lastTokenUsage == null) {
                throw new IllegalStateException("No token count found in session file");
            }

            return new UsageSnapshot(
                    provider(),
                    readTokens(lastTokenUsage, "input_tokens"),
                    readTokens(lastTokenUsage, "output_tokens"),
                    readTokens(lastTokenUsage, "cached_input_tokens"),
                    Instant.now()
            );
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read the Codex session", e);
        }

    }

    private Long readTokens(final JsonNode tokenUsage, final String field) {
        final var value = tokenUsage.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("Invalid token count for field " + field);
        }

        final var tokens = value.longValue();

        if (tokens < 0) {
            throw new IllegalStateException("Negative token count for field " + field);
        }

        return tokens;
    }
}
