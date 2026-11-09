package com.llmeter.personal.quota.controller;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import com.llmeter.personal.quota.service.QuotaService;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuotaControllerTest {
    private static final class Source implements LlmProviderAdapter {
        private final ProviderType provider;
        private Optional<QuotaSnapshot> quota = Optional.empty();
        private ProviderCollectionException failure;
        private int reads;
        Source(final ProviderType provider) { this.provider = provider; }
        @Override public ProviderType provider() { return provider; }
        @Override public UsageSnapshot getUsage() { throw new AssertionError("Quota must not collect usage"); }
        @Override public Optional<QuotaSnapshot> getQuota() { reads++; if (failure != null) throw failure; return quota; }
    }
    private Source adapter(final ProviderType provider) { return new Source(provider); }
    private MockMvc http(final LlmProviderAdapter... adapters) {
        return MockMvcBuilders.standaloneSetup(new QuotaController(new QuotaService(List.of(adapters)))).build();
    }
    // Validates selection happens before source access and percentages are exposed without invented token limits.
    @Test void shouldFilterBeforeReading() throws Exception {
        final var codex = adapter(ProviderType.CODEX);
        final var other = adapter(ProviderType.KIRO);
        final var time = Instant.parse("2026-10-25T12:00:00Z");
        codex.quota = Optional.of(new QuotaSnapshot(ProviderType.CODEX, null, null, null, time,
                time, "plus", "codex", List.of(new QuotaWindow("primary", 40, 300, time.plusSeconds(3600), "codex", time))));
        http(codex, other).perform(get("/api/quota").param("provider", "CODEX"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("OBSERVED"))
                .andExpect(jsonPath("$[0].snapshot.planType").value("plus"))
                .andExpect(jsonPath("$[0].snapshot.limit").isEmpty())
                .andExpect(jsonPath("$[0].snapshot.windows[0].usedPercent").value(40.0));
        assertThat(other.reads).isZero();
        assertThat(codex.reads).isEqualTo(1);
    }
    // Validates quota failure stays isolated, exposes no private detail and preserves unsupported providers.
    @Test void shouldIsolateSafeProviderStatuses() throws Exception {
        final var codex = adapter(ProviderType.CODEX);
        final var other = adapter(ProviderType.KIRO);
        codex.failure = new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "PRIVATE_PATH");
        http(codex, other).perform(get("/api/quota"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("ERROR"))
                .andExpect(jsonPath("$[1].status").value("UNAVAILABLE"))
                .andExpect(content().string(not(containsString("PRIVATE_PATH"))));
    }
    // Validates invalid providers are rejected and an absent configured source is an empty result.
    @Test void shouldValidateSelection() throws Exception {
        http().perform(get("/api/quota").param("provider", "UNKNOWN")).andExpect(status().isBadRequest());
        http().perform(get("/api/quota")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
