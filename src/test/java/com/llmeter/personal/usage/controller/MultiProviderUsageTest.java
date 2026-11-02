package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.antigravity.service.adapter.AntigravityAdapter;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MultiProviderUsageTest {
    // Validates both real adapters expose normalized observations, scoped coverage, and strict provider selection over HTTP.
    @Test
    void shouldCollectBothProvidersAndFilterBeforeReading(@TempDir final Path root) throws Exception {
        final var codex = Files.createDirectory(root.resolve("codex"));
        Files.writeString(codex.resolve("session.jsonl"), """
                {"ordinal":0,"type":"session_meta","payload":{"id":"synthetic-codex","history_mode":"paginated"}}
                {"ordinal":1,"timestamp":"2026-11-01T12:00:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"output_tokens":10,"cached_input_tokens":50},"last_token_usage":{"input_tokens":100,"output_tokens":10,"cached_input_tokens":50}}}}
                """);
        final var brain = Files.createDirectory(root.resolve("brain"));
        final var full = brain.resolve("synthetic-session/.system_generated/logs/transcript_full.jsonl");
        Files.createDirectories(full.getParent());
        Files.writeString(full, """
                {"step_index":0,"type":"USER_INPUT","source":"USER_EXPLICIT","status":"DONE","created_at":"2026-11-01T12:00:00Z"}
                {"step_index":1,"type":"PLANNER_RESPONSE","source":"MODEL","status":"DONE","created_at":"2026-11-01T12:01:00Z","input_tokens":20,"cache_read_tokens":80,"output_tokens":10}
                """);
        final MockMvc http = MockMvcBuilders.standaloneSetup(new UsageController(new UsageService(List.of(
                CodexAdapter.forDirectory(codex), new AntigravityAdapter(brain)))))
                .setControllerAdvice(new UsageExceptionHandler()).build();
        final String query = "/api/usage/daily/collection?from=2026-11-01&to=2026-11-01&zone=UTC";
        http.perform(get(query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.length()").value(2)).andExpect(jsonPath("$.coverage.length()").value(2))
                .andExpect(jsonPath("$.usage[1].inputTokens").value(100));
        http.perform(get(query + "&provider=ANTIGRAVITY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.length()").value(1)).andExpect(jsonPath("$.coverage.length()").value(1))
                .andExpect(jsonPath("$.coverage[0].provider").value("ANTIGRAVITY"));
        Files.writeString(full, "{");
        http.perform(get(query)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString(brain.toString()))));
        http.perform(get(query + "&provider=CODEX")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage[0].provider").value("CODEX"));
        http.perform(get("/api/usage?provider=CODEX")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(get("/api/usage/daily?from=2026-11-01&to=2026-11-01&provider=CODEX"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(get(query + "&provider=unknown")).andExpect(status().isBadRequest());
    }
}
