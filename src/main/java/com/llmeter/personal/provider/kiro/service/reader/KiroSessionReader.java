package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.provider.kiro.domain.KiroCreditEvent;
import com.llmeter.personal.provider.kiro.dto.KiroSessionUsage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class KiroSessionReader {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public KiroSessionUsage read(final Path folder) {
        try {
            final var metadata = parse(Files.readString(folder.resolve("session.json")));
            final String id = text(metadata, "id");
            if (!id.equals(folder.getFileName().toString())) throw failure(ProviderCollectionFailure.INVALID_IDENTITY);
            if (!"1.0.0".equals(text(metadata, "schemaVersion")) || !metadata.path("dataModelVersion").isIntegralNumber()
                    || !metadata.path("dataModelVersion").canConvertToLong() || metadata.path("dataModelVersion").longValue() != 1) throw failure(ProviderCollectionFailure.UNSUPPORTED_HISTORY);
            final var messages = folder.resolve("messages.jsonl");
            if (Files.notExists(messages)) return new KiroSessionUsage(id, List.of());
            final Map<String, KiroCreditEvent> events = new HashMap<>();
            final Map<String, KiroCreditEvent> executions = new HashMap<>();
            try (final var reader = Files.newBufferedReader(messages)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    final var row = parse(line);
                    final var payload = row.path("payload");
                    final String type = text(payload, "type");
                    if (!type.equals("usage_summary")) continue;
                    if (!List.of("success", "failed", "aborted").contains(text(payload, "status"))) {
                        throw failure(ProviderCollectionFailure.UNSUPPORTED_HISTORY);
                    }
                    final var summaries = payload.get("promptTurnSummaries");
                    if (summaries == null || !summaries.isArray() || summaries.isEmpty()) {
                        throw failure(ProviderCollectionFailure.INVALID_METRICS);
                    }
                    BigDecimal credits = BigDecimal.ZERO;
                    for (final var summary : summaries) {
                        if (!"credit".equals(text(summary, "unit"))) throw failure(ProviderCollectionFailure.UNSUPPORTED_HISTORY);
                        final var amount = summary.get("usage");
                        if (amount == null || !amount.isNumber()) throw failure(ProviderCollectionFailure.INVALID_METRICS);
                        final var decimal = amount.decimalValue();
                        KiroCreditEvent.validate(decimal);
                        credits = credits.add(decimal);
                        KiroCreditEvent.validate(credits);
                    }
                    final Instant timestamp;
                    try { timestamp = Instant.parse(text(row, "timestamp")); }
                    catch (final DateTimeException e) { throw failure(ProviderCollectionFailure.INVALID_TIMESTAMP); }
                    final var event = new KiroCreditEvent(text(row, "id"), text(payload, "executionId"), timestamp, credits.stripTrailingZeros());
                    final var previous = events.putIfAbsent(event.id(), event);
                    final var execution = executions.putIfAbsent(event.executionId(), event);
                    if ((previous != null && !previous.equals(event)) || (execution != null && !execution.equals(event))) {
                        throw failure(ProviderCollectionFailure.CONFLICTING_DATA);
                    }
                }
            }
            return new KiroSessionUsage(id, events.values().stream().sorted(java.util.Comparator.comparing(KiroCreditEvent::id)).toList());
        } catch (final IOException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not read Kiro session", e);
        }
    }

    private JsonNode parse(final String value) {
        try {
            final var node = mapper.readTree(value);
            if (node == null || !node.isObject()) throw failure(ProviderCollectionFailure.INVALID_JSON);
            return node;
        } catch (final JacksonException e) { throw failure(ProviderCollectionFailure.INVALID_JSON); }
    }

    private String text(final JsonNode node, final String name) {
        final var value = node.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank()) throw failure(ProviderCollectionFailure.INVALID_IDENTITY);
        return value.stringValue();
    }

    private static ProviderCollectionException failure(final ProviderCollectionFailure category) {
        return new ProviderCollectionException(category, "Kiro session could not be collected safely");
    }
}
