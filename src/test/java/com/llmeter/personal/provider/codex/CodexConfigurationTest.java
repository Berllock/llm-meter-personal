package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.usage.controller.UsageController;
import com.llmeter.personal.usage.controller.UsageExceptionHandler;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodexConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CodexConfiguration.class, UsageService.class,
                    UsageController.class, UsageExceptionHandler.class);

    // Verifies that no Codex source property leaves the service without an adapter.
    @Test
    void shouldRemainDisabledWithoutSourceConfiguration() {
        contextRunner.run((final var context) -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(LlmProviderAdapter.class);
            assertThat(context.getBean(UsageService.class).getUsage()).isEmpty();
        });
    }

    // Verifies that the original file property still registers one adapter and injects it into the service.
    @Test
    void shouldPreserveSingleFileConfiguration() throws Exception {
        final var resource = Objects.requireNonNull(
                getClass().getResource("/codex/session.jsonl"), "Test resource not found"
        );
        final var file = Path.of(resource.toURI());

        contextRunner.withPropertyValues("llm-meter.codex.session-file=" + file).run((final var context) -> {
            assertThat(context).hasNotFailed().hasSingleBean(LlmProviderAdapter.class);
            final var snapshots = context.getBean(UsageService.class).getUsage();
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().inputTokens()).isEqualTo(180L);
        });
    }

    // Verifies directory configuration, dependency injection, recursive discovery, deduplication, and HTTP serialization.
    @Test
    void shouldExposeAggregatedDirectoryUsageThroughHttp(@TempDir final Path directory) throws IOException {
        final var nested = Files.createDirectory(directory.resolve("nested"));
        final var first = directory.resolve("first.jsonl");
        Files.writeString(first, """
                {"type":"session_meta","payload":{"id":"session-a"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"output_tokens":40,"cached_input_tokens":20}}}}
                """);
        Files.copy(first, nested.resolve("copy.jsonl"));
        Files.writeString(nested.resolve("second.jsonl"), """
                {"type":"session_meta","payload":{"id":"session-b"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":200,"output_tokens":60,"cached_input_tokens":30}}}}
                """);
        Files.writeString(directory.resolve("notes.txt"), "Ignored non-session file");

        contextRunner.withPropertyValues("llm-meter.codex.sessions-directory=" + directory).run((final var context) -> {
            assertThat(context).hasNotFailed().hasSingleBean(LlmProviderAdapter.class);
            final var mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(UsageController.class))
                    .setControllerAdvice(context.getBean(UsageExceptionHandler.class)).build();

            mockMvc.perform(get("/api/usage"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].provider").value("CODEX"))
                    .andExpect(jsonPath("$[0].inputTokens").value(300))
                    .andExpect(jsonPath("$[0].outputTokens").value(100))
                    .andExpect(jsonPath("$[0].cachedTokens").value(50));
        });
    }

    // Verifies that an unavailable directory fails collection with the public HTTP 503 contract.
    @Test
    void shouldReturnServiceUnavailableForMissingDirectory(@TempDir final Path directory) {
        contextRunner.withPropertyValues("llm-meter.codex.sessions-directory=" + directory.resolve("missing"))
                .run((final var context) -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LlmProviderAdapter.class);
                    final var mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(UsageController.class))
                            .setControllerAdvice(context.getBean(UsageExceptionHandler.class)).build();

                    mockMvc.perform(get("/api/usage"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.detail").value(
                                    "Usage data is temporarily unavailable. Please try again later."));
                });
    }

    // Verifies that selecting both sources fails startup instead of registering two Codex adapters.
    @Test
    void shouldRejectSimultaneousFileAndDirectoryConfiguration(@TempDir final Path directory) {
        contextRunner.withPropertyValues(
                "llm-meter.codex.session-file=" + directory.resolve("session.jsonl"),
                "llm-meter.codex.sessions-directory=" + directory
        ).run((final var context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Configure only one of");
        });
    }

    // Verifies that an explicitly configured blank path fails startup rather than referring to the working directory.
    @ParameterizedTest
    @ValueSource(strings = {"llm-meter.codex.session-file=", "llm-meter.codex.sessions-directory= "})
    void shouldRejectBlankSourcePaths(final String property) {
        contextRunner.withPropertyValues(property).run((final var context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Codex session source path must not be blank");
        });
    }
}
