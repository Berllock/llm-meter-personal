package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import com.llmeter.personal.usage.service.UsageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsageDiagnosticPrivacyTest {

    // Verifies that every internal failure category preserves the generic HTTP 503 contract for both endpoints.
    @ParameterizedTest
    @EnumSource(ProviderCollectionFailure.class)
    void shouldKeepDiagnosticDetailsOutOfHttp(final ProviderCollectionFailure category) throws Exception {
        final var adapter = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.CODEX, 0L, 0L, 0L, Instant.EPOCH)) {
            @Override
            public UsageSnapshot getUsage() {
                throw failure();
            }

            @Override
            public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
                throw failure();
            }

            private ProviderCollectionException failure() {
                return new ProviderCollectionException(category, "PRIVATE_SESSION_CONTENT", new RuntimeException("PRIVATE_CAUSE"));
            }
        };
        final var http = MockMvcBuilders.standaloneSetup(new UsageController(new UsageService(List.of(adapter))))
                .setControllerAdvice(new UsageExceptionHandler()).build();

        for (final var path : List.of("/api/usage", "/api/usage/daily")) {
            http.perform(get(path).param("from", "2026-10-24").param("to", "2026-10-26"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Usage collection unavailable"))
                    .andExpect(jsonPath("$.detail").value("Usage data is temporarily unavailable. Please try again later."))
                    .andExpect(jsonPath("$.failure").doesNotExist())
                    .andExpect(jsonPath("$.category").doesNotExist())
                    .andExpect(jsonPath("$.cause").doesNotExist())
                    .andExpect(content().string(not(containsString("PRIVATE_SESSION_CONTENT"))))
                    .andExpect(content().string(not(containsString("PRIVATE_CAUSE"))));
        }
    }
}
