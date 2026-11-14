package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaForecast;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaHistoryStoreTest {
    @TempDir Path directory;
    // Validates a fresh process can restore exact credit strings and date-only renewal/expiry from the archive.
    @Test void shouldRestoreCreditsAfterRestart() throws Exception {
        new QuotaHistoryStore(directory).append(CONTEXT, kiro(), NOW);
        final var saved = new QuotaHistoryStore(directory).read(ProviderType.KIRO, CONTEXT, NOW).getFirst();
        assertThat(saved.accountVerified()).isFalse();
        assertThat(saved.savedAt()).isEqualTo(NOW);
        assertThat(saved.snapshot()).isEqualTo(kiro());
        assertThat(saved.snapshot().creditQuota().allowances().getFirst().remaining()).isEqualTo("37.876543210987654322");
        assertThat(Files.readString(directory.resolve("kiro.json"))).doesNotContain("forecast", "accessToken", "synthetic-source");
    }
    // Validates polling/derived forecasts never create samples, while a distinct source event or reset stays separate.
    @Test void shouldDeduplicatePollsAndPreserveCycles() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        final var first = codex(NOW, 20);
        store.append(CONTEXT, first, NOW);
        final var forecast = new QuotaForecast(QuotaForecast.Status.STALE, 1, null, null, null, null, null, null);
        final var window = first.windows().getFirst();
        final var repeat = new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW.plusSeconds(5), NOW, "plus", "codex",
                List.of(new QuotaWindow("primary", 20, 300L, window.resetAt(), "codex", NOW, forecast)));
        store.append(CONTEXT, repeat, NOW.plusSeconds(5));
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW)).hasSize(1);
        store.append(CONTEXT, codex(NOW.plusSeconds(60), 20), NOW.plusSeconds(60));
        final var renewed = new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW.plusSeconds(120), NOW.plusSeconds(120), "plus", "codex",
                List.of(new QuotaWindow("primary", 0, 300, NOW.plusSeconds(36_000), "codex", NOW.plusSeconds(120))));
        store.append(CONTEXT, renewed, NOW.plusSeconds(120));
        final var entries = new QuotaHistoryStore(directory).read(ProviderType.CODEX, CONTEXT, NOW);
        assertThat(entries).hasSize(3);
        assertThat(entries.getFirst().snapshot().windows().getFirst().forecast()).isNull();
        assertThat(entries.getFirst().snapshot().collectedAt()).isEqualTo(NOW);
        assertThat(entries.getLast().snapshot().windows().getFirst().resetAt()).isEqualTo(NOW.plusSeconds(36_000));
    }
    // Validates changed source folders have independent views without storing their original paths.
    @Test void shouldScopeEntriesToConfiguredSource() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        final var other = QuotaHistoryCodec.hash("another-private-folder");
        store.append(CONTEXT, codex(NOW, 20), NOW);
        store.append(other, codex(NOW, 60), NOW);
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW)).hasSize(1);
        assertThat(store.read(ProviderType.CODEX, other, NOW).getFirst().snapshot().windows().getFirst().usedPercent()).isEqualTo(60);
        assertThat(Files.readString(directory.resolve("codex.json"))).doesNotContain("another-private-folder");
    }
    // Validates age and count retention keep the newest observations and remove expired records on the next write.
    @Test void shouldBoundRetentionAndCount() throws Exception {
        final var store = new QuotaHistoryStore(directory, 3, 100_000);
        for (int i = 0; i < 5; i++) store.append(CONTEXT, codex(NOW.plusSeconds(i), i), NOW.plusSeconds(i));
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW)).hasSize(3);
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW).getFirst().snapshot().windows().getFirst().usedPercent()).isEqualTo(2);
        final var later = NOW.plus(Duration.ofDays(31));
        assertThat(store.read(ProviderType.CODEX, CONTEXT, later)).isEmpty();
        store.append(CONTEXT, codex(later, 10), later);
        assertThat(new QuotaHistoryStore(directory, 3, 100_000).read(ProviderType.CODEX, CONTEXT, later)).hasSize(1);
    }
    // Validates byte limits prune old entries and an oversized new entry cannot destroy the existing archive.
    @Test void shouldBoundEncodedBytes() throws Exception {
        final var store = new QuotaHistoryStore(directory, 100, 1_600);
        for (int i = 0; i < 5; i++) store.append(CONTEXT, codex(NOW.plusSeconds(i), i), NOW.plusSeconds(i));
        assertThat(Files.size(directory.resolve("codex.json"))).isLessThanOrEqualTo(1_600);
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW)).hasSizeBetween(1, 2);
        final var original = Files.readAllBytes(directory.resolve("codex.json"));
        assertThatThrownBy(() -> new QuotaHistoryStore(directory, 100, 10).append(CONTEXT, codex(NOW.plusSeconds(10), 10), NOW))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(directory.resolve("codex.json"))).isEqualTo(original);
    }
    // Validates corrupt, unsupported and modified archives remain intact rather than being reset to empty history.
    @Test void shouldPreserveUnreadableHistory() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        store.append(CONTEXT, codex(NOW, 20), NOW);
        final var file = directory.resolve("codex.json");
        final var valid = Files.readString(file);
        for (final var bad : List.of("{", valid.replace("\"version\":2", "\"version\":99"), valid.replace("20.0", "21.0"))) {
            Files.writeString(file, bad);
            assertThatThrownBy(() -> store.read(ProviderType.CODEX, CONTEXT, NOW)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> store.append(CONTEXT, codex(NOW.plusSeconds(60), 30), NOW)).isInstanceOf(IOException.class);
            assertThat(Files.readString(file)).isEqualTo(bad);
        }
    }
    // Validates no persistence work occurs when disabled, and lock contention never blocks a live quota indefinitely.
    @Test void shouldDisableAndFailFastOnLockContention() throws Exception {
        new QuotaHistoryStore(null).append(CONTEXT, codex(NOW, 20), NOW);
        assertThat(new QuotaHistoryStore(null).read(ProviderType.CODEX, CONTEXT, NOW)).isEmpty();
        final var store = new QuotaHistoryStore(directory);
        store.append(CONTEXT, codex(NOW, 20), NOW);
        try (final var channel = FileChannel.open(directory.resolve("codex.lock"), StandardOpenOption.WRITE); final var lock = channel.lock()) {
            assertThatThrownBy(() -> store.append(CONTEXT, codex(NOW.plusSeconds(1), 21), NOW)).isInstanceOf(RuntimeException.class);
        }
        assertThat(store.read(ProviderType.CODEX, CONTEXT, NOW)).hasSize(1);
    }

    // Validates archive files are private on POSIX and symlinks cannot redirect reads or overwrite another file.
    @Test void shouldProtectPrivateFilesAndRejectSymlinks() throws Exception {
        final var store = new QuotaHistoryStore(directory);
        store.append(CONTEXT, codex(NOW, 20), NOW);
        final var file = directory.resolve("codex.json");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            final var target = directory.resolve("untouched.txt");
            Files.writeString(target, "SYNTHETIC_PRIVATE_FILE");
            Files.delete(file);
            Files.createSymbolicLink(file, target);
            assertThatThrownBy(() -> store.read(ProviderType.CODEX, CONTEXT, NOW)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> store.append(CONTEXT, codex(NOW, 30), NOW)).isInstanceOf(IOException.class);
            assertThat(Files.readString(target)).isEqualTo("SYNTHETIC_PRIVATE_FILE");
        }
    }
}
