package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.kiro.service.adapter.KiroAdapter;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KiroUsageContractTest {
    // Validates exact credit-string serialization, token unavailability, all-agent composition, strict failures and pre-I/O provider filtering.
    @Test
    void shouldExposeCreditsWithoutClaimingTokenTotals(@TempDir final Path root) throws Exception {
        final var kiro = Files.createDirectory(root.resolve("kiro"));
        final var folder = Files.createDirectories(kiro.resolve("session-a"));
        Files.writeString(folder.resolve("session.json"), "{\"schemaVersion\":\"1.0.0\",\"dataModelVersion\":1,\"id\":\"session-a\"}");
        final var file = folder.resolve("messages.jsonl");
        Files.writeString(file, """
                {"id":"event-a","timestamp":"2026-11-03T12:00:00Z","payload":{"type":"usage_summary","executionId":"execution-a","status":"success","promptTurnSummaries":[{"unit":"credit","usage":0.1},{"unit":"credit","usage":0.2}]}}
                """);
        final var codex = Files.createDirectory(root.resolve("codex"));
        Files.writeString(codex.resolve("session.jsonl"), """
                {"ordinal":0,"type":"session_meta","payload":{"id":"synthetic-codex","history_mode":"paginated"}}
                {"ordinal":1,"timestamp":"2026-11-03T12:00:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"output_tokens":10,"cached_input_tokens":50},"last_token_usage":{"input_tokens":100,"output_tokens":10,"cached_input_tokens":50}}}}
                """);
        final var http = MockMvcBuilders.standaloneSetup(new UsageController(new UsageService(List.of(
                CodexAdapter.forDirectory(codex), new KiroAdapter(kiro)))))
                .setControllerAdvice(new UsageExceptionHandler()).build();
        final String query = "/api/usage/daily/collection?from=2026-11-03&to=2026-11-03&zone=UTC";
        http.perform(get(query)).andExpect(status().isOk()).andExpect(jsonPath("$.usage.length()").value(2));
        http.perform(get(query + "&provider=KIRO")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.length()").value(1)).andExpect(jsonPath("$.usage[0].credits").value("0.3"))
                .andExpect(jsonPath("$.usage[0].inputTokens").value(nullValue()))
                .andExpect(jsonPath("$.coverage[0].tokenCountsAvailable").value(false));
        http.perform(get("/api/usage?provider=KIRO")).andExpect(status().isOk()).andExpect(jsonPath("$[0].credits").value("0.3"));
        http.perform(get("/api/usage/daily?from=2026-11-03&to=2026-11-03&provider=KIRO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].credits").value("0.3"));
        Files.writeString(file, "{");
        http.perform(get(query)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString(root.toString()))));
        http.perform(get(query + "&provider=CODEX")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage[0].inputTokens").value(100));
    }
}
