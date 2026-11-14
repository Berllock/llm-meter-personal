package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.QuotaAccountEvidence;
import com.llmeter.personal.quota.dto.QuotaComparability.AccountStatus;
import com.llmeter.personal.quota.dto.QuotaHistoryEntry;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaContextPersistenceTest {
    @TempDir Path directory;
    private final QuotaHistoryCodec codec = new QuotaHistoryCodec();
    private QuotaSnapshot identified(final String account) {
        final var base = codex(NOW, 20);
        return new QuotaSnapshot(base.provider(), null, null, null, NOW, NOW, base.planType(), base.limitId(), base.windows(),
                null, 0, null, new QuotaAccountEvidence(account));
    }
    // Validates account changes cannot deduplicate or match contexts, and raw IDs remain outside archives and API entries.
    @Test void shouldPersistOnlyOpaqueAccountContext() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        store.append(CONTEXT, identified("SYNTHETIC_PRIVATE_ACCOUNT_A"), NOW);
        store.append(CONTEXT, identified("SYNTHETIC_PRIVATE_ACCOUNT_B"), NOW.plusSeconds(60));
        final var restored = new QuotaHistoryStore(directory).read(ProviderType.CODEX, CONTEXT, NOW.plusSeconds(60));
        assertThat(restored).hasSize(2).allMatch(QuotaHistoryEntry::accountVerified);
        assertThat(restored).allMatch(entry -> entry.comparability().accountStatus() == AccountStatus.RECORDED_ACCOUNT);
        assertThat(restored.getFirst().comparability().groups().getFirst().key()).isNotEqualTo(restored.getLast().comparability().groups().getFirst().key());
        assertThat(restored).allMatch(entry -> entry.snapshot().accountEvidence() == null);
        assertThat(Files.readString(directory.resolve("codex.json"))).doesNotContain("SYNTHETIC_PRIVATE_ACCOUNT", "recordedAccountId", "accountEvidence");
        assertThat(codec.mapper.writeValueAsString(restored)).doesNotContain("accountFingerprint", "SYNTHETIC_PRIVATE_ACCOUNT");
        assertThat(codec.mapper.writeValueAsString(identified("SYNTHETIC_PRIVATE_ACCOUNT_A"))).doesNotContain("accountEvidence", "SYNTHETIC_PRIVATE_ACCOUNT_A");
        assertThat(codec.mapper.writeValueAsString(new QuotaAccountEvidence("SYNTHETIC_PRIVATE_ACCOUNT_A")))
                .doesNotContain("recordedAccountId", "SYNTHETIC_PRIVATE_ACCOUNT_A");
    }
    // Validates v1 records stay unverified with unchanged IDs/times and migrate atomically only on a later successful write.
    @Test void shouldMigrateLegacyArchiveWithoutInventingIdentity() throws Exception {
        final var snapshot = codex(NOW, 20);
        final String oldId = codec.identity(CONTEXT, snapshot);
        final var old = new QuotaHistoryEntry(oldId, NOW, false, snapshot);
        final var bytes = codec.mapper.writeValueAsBytes(Map.of("version", 1, "observations", List.of(Map.of("context", CONTEXT, "observation", old))));
        final var file = directory.resolve("codex.json");
        Files.write(file, bytes);
        final var store = new QuotaHistoryStore(directory);
        final var legacy = store.read(ProviderType.CODEX, CONTEXT, NOW).getFirst();
        assertThat(legacy.accountVerified()).isFalse();
        assertThat(legacy.comparability().accountStatus()).isEqualTo(AccountStatus.LEGACY);
        assertThat(legacy.id()).isEqualTo(oldId);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        store.append(CONTEXT, identified("SYNTHETIC_PRIVATE_ACCOUNT_A"), NOW.plusSeconds(60));
        final var restored = new QuotaHistoryStore(directory).read(ProviderType.CODEX, CONTEXT, NOW.plusSeconds(60));
        assertThat(restored).hasSize(2);
        assertThat(restored.getFirst().id()).isEqualTo(oldId);
        assertThat(restored.getFirst().savedAt()).isEqualTo(NOW);
        assertThat(restored.getFirst().accountVerified()).isFalse();
        assertThat(restored.getLast().accountVerified()).isTrue();
        assertThat(codec.mapper.readTree(Files.readAllBytes(file)).path("version").intValue()).isEqualTo(2);
    }
    // Validates edited comparison keys or identity flags cannot silently reclassify saved observations.
    @Test void shouldRejectInconsistentSavedEvidence() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        store.append(CONTEXT, identified("SYNTHETIC_PRIVATE_ACCOUNT_A"), NOW);
        final var file = directory.resolve("codex.json");
        final var valid = Files.readString(file);
        final String key = store.read(ProviderType.CODEX, CONTEXT, NOW).getFirst().comparability().groups().getFirst().key();
        for (final var bad : List.of(valid.replace(key, "f".repeat(64)), valid.replace("\"accountVerified\":true", "\"accountVerified\":false"))) {
            Files.writeString(file, bad);
            assertThatThrownBy(() -> store.read(ProviderType.CODEX, CONTEXT, NOW)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> store.append(CONTEXT, identified("SYNTHETIC_PRIVATE_ACCOUNT_A"), NOW)).isInstanceOf(IOException.class);
            assertThat(Files.readString(file)).isEqualTo(bad);
        }
    }
}
