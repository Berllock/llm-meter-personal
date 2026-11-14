package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.ProviderQuota;
import com.llmeter.personal.quota.dto.QuotaHistoryResponse;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.service.QuotaService;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaHistoryServiceTest {
    @TempDir Path directory;
    private QuotaHistoryService history() {
        return new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.CODEX, CONTEXT, ProviderType.KIRO, CONTEXT), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private LlmProviderAdapter source(final QuotaSnapshot snapshot) {
        return new FakeLlmProviderAdapter(new UsageSnapshot(snapshot.provider(), null, null, null, NOW)) {
            @Override public Optional<QuotaSnapshot> getQuota() { return Optional.of(snapshot); }
        };
    }
    // Validates archive writes remain independent of live quota, with corrupt-source errors isolated by provider.
    @Test void shouldKeepLiveQuotaAvailableWhenArchiveFails() throws Exception {
        final var history = history();
        Files.writeString(directory.resolve("codex.json"), "PRIVATE_CORRUPT_ARCHIVE");
        final var live = new QuotaService(List.of(source(codex(NOW, 20)), source(kiro())), history).collect(null);
        assertThat(live).extracting(ProviderQuota::status).containsOnly(ProviderQuota.Status.OBSERVED);
        final var response = history.read(null, 20);
        assertThat(response.status()).isEqualTo(QuotaHistoryResponse.Status.PARTIAL);
        assertThat(response.failures()).containsExactly(ProviderType.CODEX);
        assertThat(response.observations()).hasSize(1);
        assertThat(response.observations().getFirst().snapshot().provider()).isEqualTo(ProviderType.KIRO);
        assertThat(history.read(ProviderType.KIRO, 20).status()).isEqualTo(QuotaHistoryResponse.Status.READY);
        assertThat(history.read(ProviderType.CODEX, 20).status()).isEqualTo(QuotaHistoryResponse.Status.ERROR);
        assertThat(Files.readString(directory.resolve("codex.json"))).isEqualTo("PRIVATE_CORRUPT_ARCHIVE");
    }
    // Validates history survives service recreation and can be read without invoking live providers or their authentication.
    @Test void shouldRestoreWithoutCollectingLiveQuota() {
        history().record(codex(NOW, 20));
        final LlmProviderAdapter unavailable = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.CODEX, null, null, null, NOW)) {
            @Override public Optional<QuotaSnapshot> getQuota() { throw new AssertionError("History must never query a provider"); }
        };
        final var saved = new QuotaService(List.of(unavailable), history()).history(ProviderType.CODEX, 20);
        assertThat(saved.status()).isEqualTo(QuotaHistoryResponse.Status.READY);
        assertThat(saved.observations()).hasSize(1);
    }
    // Validates newest-first bounded results do not change cycle metadata or promote unverified account identity.
    @Test void shouldLimitAndSortSavedObservations() {
        final var history = history();
        history.record(codex(NOW, 20));
        final var later = new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.CODEX, CONTEXT, ProviderType.KIRO, CONTEXT),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        later.record(kiro());
        final var response = later.read(null, 1);
        assertThat(response.observations()).hasSize(1);
        assertThat(response.observations().getFirst().savedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(response.observations().getFirst().snapshot().provider()).isEqualTo(ProviderType.KIRO);
        assertThat(response.observations().getFirst().accountVerified()).isFalse();
        assertThatThrownBy(() -> history.read(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.read(null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(QuotaHistoryService.disabled().read(null, 20).status()).isEqualTo(QuotaHistoryResponse.Status.DISABLED);
    }
}
