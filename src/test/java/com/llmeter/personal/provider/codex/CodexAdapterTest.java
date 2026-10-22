package com.llmeter.personal.provider.codex;

import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CodexAdapterTest {

    @Test
    void deveLerContagemDeTokensDoEvento() {
        final var linha = """
                {
                    "type": "event_msg",
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {
                                "input_tokens": 100,
                                "output_tokens": 40,
                                "cached_input_tokens": 20
                            }
                        }
                    }
                }
                """;
        final var mapper = new JsonMapper();

        final var evento = mapper.readTree(linha);
        final var contagem = evento
                .path("payload")
                .path("info")
                .path("total_token_usage");

        assertEquals(100L, contagem.path("input_tokens").asLong());
        assertEquals(40L, contagem.path("output_tokens").asLong());
        assertEquals(20L, contagem.path("cached_input_tokens").asLong());
    }
}
