package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class UsageCollectionControllerTest {
    @TempDir Path directory;
    private MockMvc http() {
        return MockMvcBuilders.standaloneSetup(new UsageController(new UsageService(List.of(CodexAdapter.forDirectory(directory)))))
                .setControllerAdvice(new UsageExceptionHandler()).build();
    }
    // Validates the envelope exposes partial status and safe source counts without revealing identities or paths.
    @Test void shouldSerializeObservedUsageAndCoverage() throws Exception {
        Files.copy(Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI()), directory.resolve("known.jsonl"));
        Files.writeString(directory.resolve("private-name.jsonl"), "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"PRIVATE_ID\",\"history_mode\":\"paginated\"}}\n");
        http().perform(get("/api/usage/daily/collection").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.from").value("2026-10-24")).andExpect(jsonPath("$.zone").value("UTC"))
                .andExpect(jsonPath("$.usage[0].inputTokens").value(160))
                .andExpect(jsonPath("$.coverage[0].provider").value("CODEX"))
                .andExpect(jsonPath("$.coverage[0].observedSessions").value(1))
                .andExpect(jsonPath("$.coverage[0].pendingSessions").value(1))
                .andExpect(content().string(not(containsString("PRIVATE_ID"))))
                .andExpect(content().string(not(containsString("private-name"))))
                .andExpect(content().string(not(containsString(directory.toString()))));
    }
    // Validates no configured providers returns a complete empty collection without invented coverage.
    @Test void shouldReturnCompleteEmptyCollectionWithoutProviders() throws Exception {
        final var http = MockMvcBuilders.standaloneSetup(new UsageController(new UsageService(List.of()))).build();
        http.perform(get("/api/usage/daily/collection").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.usage").isEmpty()).andExpect(jsonPath("$.coverage").isEmpty());
    }
    // Validates malformed and reversed dates retain HTTP 400 on the coverage endpoint.
    @ParameterizedTest @ValueSource(strings = { "invalid", "2026-02-30", "2027-01-01" })
    void shouldRejectInvalidPeriods(final String from) throws Exception {
        http().perform(get("/api/usage/daily/collection").param("from", from).param("to", "2026-10-26"))
                .andExpect(status().isBadRequest());
    }
    // Validates an empty file remains an unsafe source error, not a pending identified session.
    @Test void shouldKeepUnsafeFailuresGeneric() throws Exception {
        Files.writeString(directory.resolve("PRIVATE_PATH.jsonl"), "");
        http().perform(get("/api/usage/daily/collection").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.usage").doesNotExist())
                .andExpect(content().string(not(containsString("PRIVATE_PATH"))));
    }
}
