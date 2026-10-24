package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.codex.CodexAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsageControllerTest {

    // Verifies that GET /api/usage returns all snapshots as JSON, preserving null metrics and timestamps.
    @Test
    void shouldReturnUsageSnapshotsAsJson() throws Exception {
        final var collectedAt = Instant.parse("2026-10-22T12:00:00Z");
        final var codexUsage = new UsageSnapshot(
                ProviderType.CODEX, 100L, 40L, null, collectedAt
        );
        final var claudeUsage = new UsageSnapshot(
                ProviderType.CLAUDE, 200L, 60L, 30L, collectedAt
        );
        final List<LlmProviderAdapter> adapters = List.of(
                new FakeLlmProviderAdapter(codexUsage),
                new FakeLlmProviderAdapter(claudeUsage)
        );
        final var service = new UsageService(adapters);
        final var mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(service))
                .setControllerAdvice(new UsageExceptionHandler()).build();

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].provider").value("CODEX"))
                .andExpect(jsonPath("$[0].inputTokens").value(100))
                .andExpect(jsonPath("$[0].outputTokens").value(40))
                .andExpect(jsonPath("$[0].cachedTokens").hasJsonPath())
                .andExpect(jsonPath("$[0].cachedTokens").value(nullValue()))
                .andExpect(jsonPath("$[0].collectedAt").value(collectedAt.toString()))
                .andExpect(jsonPath("$[1].provider").value("CLAUDE"))
                .andExpect(jsonPath("$[1].inputTokens").value(200))
                .andExpect(jsonPath("$[1].outputTokens").value(60))
                .andExpect(jsonPath("$[1].cachedTokens").value(30))
                .andExpect(jsonPath("$[1].collectedAt").value(collectedAt.toString()));
    }

    // Verifies that GET /api/usage returns HTTP 200 and an empty JSON array when no adapters are configured.
    @Test
    void shouldReturnEmptyJsonArrayWhenThereAreNoAdapters() throws Exception {
        final var service = new UsageService(List.of());
        final var mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(service))
                .setControllerAdvice(new UsageExceptionHandler()).build();

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("[]"));
    }

    // Verifies that file read failures produce a safe 503 response without exposing the local path or cause.
    @Test
    void shouldReturnServiceUnavailableWhenSessionCannotBeRead(@TempDir final Path directory)
            throws Exception {
        final var file = directory.resolve("private-session.jsonl");
        final var service = new UsageService(List.of(new CodexAdapter(file)));
        final var mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(service))
                .setControllerAdvice(new UsageExceptionHandler()).build();

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Usage collection unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(
                        "Usage data is temporarily unavailable. Please try again later."))
                .andExpect(jsonPath("$.instance").value("/api/usage"))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(content().string(not(containsString(file.toString()))));
    }

    // Verifies that empty, malformed, and invalid usage data share the public 503 contract without leaking content.
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{\"PRIVATE_SESSION_CONTENT\":",
            "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":-1}}}}"
    })
    void shouldReturnServiceUnavailableWhenSessionDataIsInvalid(
            final String sessionContent,
            @TempDir final Path directory
    ) throws Exception {
        final var file = directory.resolve("private-session.jsonl");
        Files.writeString(file, sessionContent);
        final var service = new UsageService(List.of(new CodexAdapter(file)));
        final var mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(service))
                .setControllerAdvice(new UsageExceptionHandler()).build();

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Usage collection unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(
                        "Usage data is temporarily unavailable. Please try again later."))
                .andExpect(jsonPath("$.instance").value("/api/usage"))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(content().string(not(containsString("PRIVATE_SESSION_CONTENT"))))
                .andExpect(content().string(not(containsString(file.toString()))));
    }
}
