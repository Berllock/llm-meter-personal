package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.codex.domain.CodexTokenCounts;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import tools.jackson.databind.JsonNode;

final class CodexTokenCountsReader {

    private CodexTokenCountsReader() {
    }

    static CodexTokenCounts read(final JsonNode node) {
        return new CodexTokenCounts(
                readToken(node, "input_tokens"),
                readToken(node, "output_tokens"),
                readToken(node, "cached_input_tokens")
        );
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
