package com.llmeter.personal.quota.controller;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.service.QuotaService;
import com.llmeter.personal.quota.service.history.QuotaHistoryService;
import com.llmeter.personal.quota.service.history.QuotaHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaHistoryControllerTest {
    @TempDir Path directory;
    private MockMvc http(final QuotaHistoryService history) {
        return MockMvcBuilders.standaloneSetup(new QuotaController(new QuotaService(List.of(), history))).build();
    }
    // Validates persisted observations survive restart and expose exact credits with separate source/query/save dates.
    @Test void shouldExposeSavedObservationsWithoutQueryingProviders() throws Exception {
        final var history = new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.KIRO, CONTEXT), Clock.fixed(NOW, ZoneOffset.UTC));
        history.record(kiro());
        final var restored = new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.KIRO, CONTEXT), Clock.fixed(NOW, ZoneOffset.UTC));
        http(restored).perform(get("/api/quota/history").param("provider", "KIRO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.observations", hasSize(1)))
                .andExpect(jsonPath("$.observations[0].accountVerified").value(false))
                .andExpect(jsonPath("$.observations[0].savedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.observations[0].snapshot.creditQuota.allowances[0].remaining").value("37.876543210987654322"))
                .andExpect(jsonPath("$.observations[0].snapshot.creditQuota.renewalDate").value("2026-12-01"))
                .andExpect(content().string(not(containsString(CONTEXT))))
                .andExpect(content().string(not(containsString(directory.toString()))));
    }
    // Validates filtering avoids a broken provider archive, exposes safe errors and rejects excessive/arbitrary query limits.
    @Test void shouldIsolateFailuresAndValidateScope() throws Exception {
        final var history = new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.CODEX, CONTEXT, ProviderType.KIRO, CONTEXT), Clock.fixed(NOW, ZoneOffset.UTC));
        history.record(kiro());
        Files.writeString(directory.resolve("codex.json"), "PRIVATE_CORRUPTION");
        http(history).perform(get("/api/quota/history").param("provider", "KIRO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        http(history).perform(get("/api/quota/history"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.failures[0]").value("CODEX"))
                .andExpect(content().string(not(containsString("PRIVATE_CORRUPTION"))));
        for (final var limit : List.of("0", "101", "invalid")) http(history).perform(get("/api/quota/history").param("limit", limit)).andExpect(status().isBadRequest());
        http(history).perform(get("/api/quota/history").param("provider", "PRIVATE_PATH")).andExpect(status().isBadRequest());
        http(QuotaHistoryService.disabled()).perform(get("/api/quota/history"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    }
}
