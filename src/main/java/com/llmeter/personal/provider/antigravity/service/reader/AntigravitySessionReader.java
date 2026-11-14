package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.antigravity.domain.AntigravityTokenCounts;
import com.llmeter.personal.provider.antigravity.dto.AntigravitySessionUsage;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

public final class AntigravitySessionReader {
    private final JsonMapper mapper = new JsonMapper();
    private record Step(String type, String source, String status, Instant timestamp, Long input, Long output, Long cached) { }

    public AntigravitySessionUsage read(final Path file, final ZoneId zone) {
        final Map<Long, Step> steps = new HashMap<>();
        final Map<LocalDate, AntigravityTokenCounts> days = new HashMap<>();
        long lastIndex = -1;
        try (final var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                final JsonNode row;
                try {
                    row = mapper.readTree(line);
                } catch (final JacksonException e) {
                    throw failure(ProviderCollectionFailure.INVALID_JSON);
                }
                if (row == null || !row.isObject()) throw failure(ProviderCollectionFailure.INVALID_JSON);
                final var position = row.get("step_index");
                if (position == null || !position.isIntegralNumber() || !position.canConvertToLong()
                        || position.longValue() < 0) throw failure(ProviderCollectionFailure.INVALID_SEQUENCE);
                final long index = position.longValue();
                final String type = text(row, "type");
                final String source = text(row, "source");
                final String status = text(row, "status");
                final Instant timestamp = timestamp(row);
                if (lastIndex == -1 && (index != 0 || !type.equals("USER_INPUT") || !source.equals("USER_EXPLICIT"))) {
                    throw failure(ProviderCollectionFailure.INVALID_IDENTITY);
                }
                final boolean hasCounts = row.has("input_tokens") || row.has("output_tokens") || row.has("cache_read_tokens");
                if (hasCounts && !(type.equals("PLANNER_RESPONSE") && source.equals("MODEL") && status.equals("DONE"))) {
                    throw failure(ProviderCollectionFailure.UNSUPPORTED_HISTORY);
                }
                if (!hasCounts && type.equals("PLANNER_RESPONSE") && status.equals("DONE")) {
                    throw failure(ProviderCollectionFailure.NO_TOKEN_COUNT);
                }
                final Long input = hasCounts ? metric(row, "input_tokens") : null;
                final Long output = hasCounts ? metric(row, "output_tokens") : null;
                final Long cached = hasCounts ? metric(row, "cache_read_tokens") : null;
                final var step = new Step(type, source, status, timestamp, input, output, cached);
                final var existing = steps.get(index);
                if (existing != null) {
                    if (!existing.equals(step)) throw failure(ProviderCollectionFailure.CONFLICTING_DATA);
                    continue;
                }
                // Step order is defined by the index; independently created events can overlap in time.
                if (index <= lastIndex) {
                    throw failure(ProviderCollectionFailure.INVALID_SEQUENCE);
                }
                lastIndex = index;
                steps.put(index, step);
                if (hasCounts) days.merge(timestamp.atZone(zone).toLocalDate(),
                        AntigravityTokenCounts.fromRaw(input, output, cached), AntigravityTokenCounts::add);
            }
        } catch (final IOException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not read Antigravity transcript", e);
        }
        if (steps.isEmpty()) throw failure(ProviderCollectionFailure.INVALID_IDENTITY);
        return new AntigravitySessionUsage(days, !days.isEmpty());
    }

    private String text(final JsonNode row, final String name) {
        final var value = row.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw failure(ProviderCollectionFailure.UNSUPPORTED_HISTORY);
        }
        return value.stringValue();
    }

    private Instant timestamp(final JsonNode row) {
        try {
            final var value = row.get("created_at");
            if (value == null || !value.isString()) throw failure(ProviderCollectionFailure.INVALID_TIMESTAMP);
            return Instant.parse(value.stringValue());
        } catch (final DateTimeException e) {
            throw failure(ProviderCollectionFailure.INVALID_TIMESTAMP);
        }
    }

    private Long metric(final JsonNode row, final String name) {
        final var value = row.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw failure(ProviderCollectionFailure.INVALID_METRICS);
        }
        return value.longValue();
    }

    private ProviderCollectionException failure(final ProviderCollectionFailure failure) {
        return new ProviderCollectionException(failure, "Antigravity transcript could not be collected safely");
    }
}
