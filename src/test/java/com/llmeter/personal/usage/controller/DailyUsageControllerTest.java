package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DailyUsageControllerTest {

    @TempDir
    Path directory;

    // Verifies directory collection through the real service and HTTP layer with local dates and deduplicated child usage.
    @Test
    void shouldExposeDailyDirectoryUsage() throws Exception {
        for (final var name : List.of("parent", "child", "fresh-child")) {
            copyFixture(name);
        }
        Files.copy(directory.resolve("child.jsonl"), directory.resolve("copy.jsonl"));

        mockMvc().perform(get("/api/usage/daily")
                        .param("from", "2026-10-25").param("to", "2026-10-26").param("zone", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].provider").value("CODEX"))
                .andExpect(jsonPath("$[0].date").value("2026-10-25"))
                .andExpect(jsonPath("$[0].zone").value("America/Sao_Paulo"))
                .andExpect(jsonPath("$[0].inputTokens").value(90))
                .andExpect(jsonPath("$[0].outputTokens").value(9))
                .andExpect(jsonPath("$[0].cachedTokens").value(45))
                .andExpect(jsonPath("$[1].date").value("2026-10-26"))
                .andExpect(jsonPath("$[1].inputTokens").value(30));
    }

    // Verifies that omitting the time zone consistently selects UTC rather than the machine's default zone.
    @Test
    void shouldDefaultToUtc() throws Exception {
        copyFixture("parent");

        mockMvc().perform(get("/api/usage/daily").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].zone").value("UTC"))
                .andExpect(jsonPath("$[0].date").value("2026-10-25"))
                .andExpect(jsonPath("$[0].inputTokens").value(160));
    }

    // Verifies that invalid dates, missing dates, reversed intervals, and unknown zones return HTTP 400.
    @ParameterizedTest
    @ValueSource(strings = {"from", "to", "invalid-date", "reversed", "zone"})
    void shouldRejectInvalidQueries(final String scenario) throws Exception {
        final var request = get("/api/usage/daily");
        if (!scenario.equals("from")) {
            request.param("from", scenario.equals("invalid-date") ? "2026-02-30" : "2026-10-25");
        }
        if (!scenario.equals("to")) {
            request.param("to", scenario.equals("reversed") ? "2026-10-24" : "2026-10-26");
        }
        if (scenario.equals("zone")) {
            request.param("zone", "Invalid/Private_Zone");
        }

        mockMvc().perform(request).andExpect(status().isBadRequest());
    }

    // Verifies that malformed or not-yet-populated sessions abort all daily results using the safe HTTP 503 contract.
    @ParameterizedTest
    @ValueSource(strings = {"{\"PRIVATE_CONTENT\":", "{\"type\":\"session_meta\",\"payload\":{\"id\":\"empty\",\"history_mode\":\"paginated\"}}"})
    void shouldNotExposePartialDailyUsageOrPrivateContent(final String invalidContent) throws Exception {
        copyFixture("parent");
        final var invalid = directory.resolve("private-session.jsonl");
        Files.writeString(invalid, invalidContent);

        mockMvc().perform(get("/api/usage/daily").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Usage collection unavailable"))
                .andExpect(jsonPath("$.detail").value("Usage data is temporarily unavailable. Please try again later."))
                .andExpect(content().string(not(containsString("PRIVATE_CONTENT"))))
                .andExpect(content().string(not(containsString(invalid.toString()))));
    }

    // Verifies that an empty configured directory produces a successful sparse daily response.
    @Test
    void shouldReturnEmptyArrayForEmptyDirectory() throws Exception {
        mockMvc().perform(get("/api/usage/daily").param("from", "2026-10-24").param("to", "2026-10-26"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    private void copyFixture(final String name) throws Exception {
        final var resource = Objects.requireNonNull(getClass().getResource("/codex/daily/" + name + ".jsonl"));
        Files.copy(Path.of(resource.toURI()), directory.resolve(name + ".jsonl"));
    }

    private MockMvc mockMvc() {
        final var service = new UsageService(List.of(CodexAdapter.forDirectory(directory)));
        return MockMvcBuilders.standaloneSetup(new UsageController(service))
                .setControllerAdvice(new UsageExceptionHandler()).build();
    }
}
