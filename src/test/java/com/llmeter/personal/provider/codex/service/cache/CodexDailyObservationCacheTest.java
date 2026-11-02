package com.llmeter.personal.provider.codex.service.cache;

import com.llmeter.personal.provider.codex.service.CodexDailyCoverageCollector;
import com.llmeter.personal.provider.codex.service.adapter.CodexAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexDailyObservationCacheTest {
    @TempDir Path directory;
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-30"), ZoneId.of("UTC"));

    private Path fixture(final String name) throws Exception {
        final var source = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI());
        return Files.copy(source, directory.resolve(name));
    }
    private static String header(final String id) {
        return "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"history_mode\":\"paginated\"}}\n";
    }
    private static String event(final int ordinal, final int input, final int lastInput) {
        return "{\"ordinal\":" + ordinal + ",\"timestamp\":\"2026-10-27T12:00:00Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":"
                + input + ",\"output_tokens\":16,\"cached_input_tokens\":80},\"last_token_usage\":{\"input_tokens\":" + lastInput + ",\"output_tokens\":0,\"cached_input_tokens\":0}}}}\n";
    }
    private ProviderDailyUsage cached(final CodexDailyObservationCache cache, final List<Path> files, final UsagePeriod requested) {
        return new CodexDailyCoverageCollector().aggregateObservations(cache.readAll(files, requested.zone()), requested);
    }
    private ProviderDailyUsage sameAsFull(final CodexDailyObservationCache cache, final List<Path> files, final UsagePeriod requested) {
        final var result = cached(cache, files, requested);
        assertThat(result).isEqualTo(new CodexDailyCoverageCollector().collect(files, requested));
        return result;
    }
    private static InputStream atEof(final InputStream source, final Runnable action) {
        return new FilterInputStream(source) {
            private boolean ended;
            private int done(final int count) {
                if (count < 0 && !ended) { ended = true; action.run(); }
                return count;
            }
            @Override public int read() throws IOException { return done(in.read()); }
            @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                return done(in.read(bytes, offset, length));
            }
        };
    }
    private static void append(final Path file, final String content) {
        try { Files.writeString(file, content, StandardOpenOption.APPEND); }
        catch (final IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    // Validates unchanged bytes reuse immutable observations while still validating every file's full content.
    @Test void shouldReuseUnchangedContent() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.statistics().parseAttempts()).isEqualTo(1);
        assertThat(cache.statistics().hits()).isEqualTo(1);
        assertThat(cache.statistics().hashedBytes()).isEqualTo(2 * Files.size(file));
    }
    // Validates same-size edits cannot bypass validation even when modification time is restored.
    @Test void shouldDetectContentChangesWithUnchangedMetadata() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        final var time = Files.getLastModifiedTime(file);
        final long size = Files.size(file);
        Files.writeString(file, Files.readString(file).replace("100", "110").replace("160", "170"));
        Files.setLastModifiedTime(file, time);
        assertThat(Files.size(file)).isEqualTo(size);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(time);
        assertThat(sameAsFull(cache, List.of(file), period).usage().getFirst().inputTokens()).isEqualTo(170L);
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
    }
    // Validates a newly appended event updates the full daily projection rather than returning the previous counters.
    @Test void shouldIncludeAppendedCounters() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        append(file, event(5, 200, 40));
        assertThat(sameAsFull(cache, List.of(file), period).usage().stream().mapToLong(row -> row.inputTokens()).sum()).isEqualTo(200L);
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
    }
    // Validates pending entries remain content-validated and enter observed coverage when their first counter arrives.
    @Test void shouldReconsiderPendingContent() throws Exception {
        final var file = Files.writeString(directory.resolve("pending.jsonl"), header("pending"));
        final var cache = new CodexDailyObservationCache();
        assertThat(sameAsFull(cache, List.of(file), period).coverage().pendingSessions()).isEqualTo(1);
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.statistics().hits()).isEqualTo(1);
        append(file, event(1, 10, 10).replace("\"output_tokens\":0", "\"output_tokens\":16").replace("\"cached_input_tokens\":0", "\"cached_input_tokens\":80"));
        assertThat(sameAsFull(cache, List.of(file), period).coverage().pendingSessions()).isZero();
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
    }
    // Validates truncation discards earlier usage, including a valid transition back to pending.
    @Test void shouldHandleTruncationWithoutReusingOldCounters() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        Files.writeString(file, header("parent"));
        assertThat(sameAsFull(cache, List.of(file), period).usage()).isEmpty();
        Files.writeString(file, "");
        assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(file), period));
        assertThat(cache.statistics().entries()).isZero();
    }
    // Validates directory discovery adds new files and prunes every cached zone for deleted files.
    @Test void shouldTrackCreatedAndDeletedFiles() throws Exception {
        final var first = fixture("first.jsonl");
        final var adapter = CodexAdapter.forDirectory(directory);
        assertThat(adapter.getDailyUsageReport(period).coverage().observedSessions()).isEqualTo(1);
        final var second = Files.writeString(directory.resolve("second.jsonl"), Files.readString(first).replace("\"parent\"", "\"second\""));
        assertThat(adapter.getDailyUsageReport(period)).isEqualTo(new CodexDailyCoverageCollector().collect(List.of(first, second), period));
        Files.delete(first);
        assertThat(adapter.getDailyUsageReport(period)).isEqualTo(new CodexDailyCoverageCollector().collect(List.of(second), period));
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(second), period);
        cache.readAll(List.of(second), ZoneId.of("Asia/Tokyo"));
        cache.readAll(List.of(), period.zone());
        assertThat(cache.statistics().entries()).isZero();
        assertThat(cache.statistics().dayBuckets()).isZero();
    }
    // Validates daily projections are keyed by zone while selecting another period reuses the same projection.
    @Test void shouldIsolateZonesAndReuseAcrossPeriods() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        final var utc = sameAsFull(cache, List.of(file), period);
        final var brazil = sameAsFull(cache, List.of(file), new UsagePeriod(period.from(), period.to(), ZoneId.of("America/Sao_Paulo")));
        assertThat(utc.usage()).hasSize(1);
        assertThat(brazil.usage()).hasSize(2);
        sameAsFull(cache, List.of(file), new UsagePeriod(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25"), period.zone()));
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
        assertThat(cache.statistics().hits()).isEqualTo(1);
    }
    // Validates a replaced file is checked by content rather than trusting its path or prior identity.
    @Test void shouldHandleReplacementAtTheSamePath() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        final var replacement = Files.writeString(directory.resolve("replacement.tmp"), Files.readString(file).replace("\"parent\"", "\"second\""));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.readAll(List.of(file), period.zone()).getFirst().sessionId()).isEqualTo("second");
    }
    // Validates a partial write still fails and recovery reparses completed content instead of falling back to a cached success.
    @Test void shouldFailSafelyDuringPartialWritesAndRecover() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        final var next = event(5, 200, 40);
        append(file, next.substring(0, next.length() / 2));
        assertThat(assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(file), period)).failure()).isEqualTo(ProviderCollectionFailure.INVALID_JSON);
        assertThat(cache.statistics().entries()).isZero();
        append(file, next.substring(next.length() / 2));
        assertThat(sameAsFull(cache, List.of(file), period).usage()).hasSize(2);
    }
    // Validates cached byte fingerprints do not hide malformed UTF-8 or silently replace invalid characters.
    @Test void shouldPreserveStrictUtf8Decoding() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        final var cachedFailure = assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(file), period));
        final var fullFailure = assertThrows(ProviderCollectionException.class, () -> new CodexDailyCoverageCollector().collect(List.of(file), period));
        assertThat(cachedFailure.failure()).isEqualTo(fullFailure.failure());
        assertThat(cache.statistics().entries()).isZero();
    }
    // Validates current validation read failures cannot be answered from an earlier cached observation.
    @Test void shouldEvictOnReadFailure() throws Exception {
        final var file = fixture("root.jsonl");
        final var fail = new AtomicBoolean();
        final var cache = new CodexDailyObservationCache(10, 100, path -> {
            if (fail.get()) { throw new IOException("PRIVATE_READ_FAILURE"); }
            return Files.newInputStream(path);
        });
        sameAsFull(cache, List.of(file), period);
        fail.set(true);
        assertThat(assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(file), period)).failure()).isEqualTo(ProviderCollectionFailure.READ_FAILURE);
        assertThat(cache.statistics().entries()).isZero();
        fail.set(false);
        sameAsFull(cache, List.of(file), period);
    }
    // Validates changes detected during hit validation trigger fresh parsing of the updated stream.
    @Test void shouldReparseIfAFileChangesDuringValidation() throws Exception {
        final var file = fixture("root.jsonl");
        final var change = new AtomicBoolean();
        final var cache = new CodexDailyObservationCache(10, 100, path -> atEof(Files.newInputStream(path), () -> {
            if (change.getAndSet(false)) { append(path, event(5, 200, 40)); }
        }));
        sameAsFull(cache, List.of(file), period);
        change.set(true);
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
        assertThat(cache.statistics().hits()).isZero();
    }
    // Validates a successfully parsed stream observed during a metadata change is returned normally but not retained.
    @Test void shouldNotRetainAnUnstableParsedStream() throws Exception {
        final var file = fixture("root.jsonl");
        final var opens = new AtomicInteger();
        final var cache = new CodexDailyObservationCache(10, 100, path -> {
            final var stream = Files.newInputStream(path);
            return opens.incrementAndGet() == 1 ? atEof(stream, () -> append(path, "\n")) : stream;
        });
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.statistics().entries()).isZero();
        sameAsFull(cache, List.of(file), period);
        assertThat(cache.statistics().entries()).isEqualTo(1);
    }
    // Validates all observed copies still participate in global conflict checks on both misses and hits.
    @Test void shouldNeverCacheAConflictAsASuccess() throws Exception {
        final var first = fixture("first.jsonl");
        final var second = fixture("second.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(first, second), period);
        Files.writeString(second, Files.readString(second).replace("100", "110").replace("160", "170"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(first, second), period)).failure()).isEqualTo(ProviderCollectionFailure.CONFLICTING_DATA);
        }
    }
    // Validates proven child contributions and unknown counters retain full-scan semantics even with duplicate inputs.
    @Test void shouldKeepAttributionAndUnknownMetrics() throws Exception {
        final var root = fixture("root.jsonl");
        final var child = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/child.jsonl")).toURI());
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(root, root, child), period);
        sameAsFull(cache, List.of(root, root, child), period);
        Files.writeString(root, Files.readString(root).replace("\"cached_input_tokens\":50", "\"cached_input_tokens\":null"));
        sameAsFull(cache, List.of(root, root, child), period);
    }
    // Validates counter resets still invalidate new results rather than using the earlier higher totals.
    @Test void shouldPreserveResetFailures() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        sameAsFull(cache, List.of(file), period);
        append(file, event(5, 1, 1));
        assertThat(assertThrows(ProviderCollectionException.class, () -> cached(cache, List.of(file), period)).failure()).isEqualTo(ProviderCollectionFailure.COUNTER_RESET);
    }
    // Validates entry eviction uses recent access and remains correct when a zone must be parsed again.
    @Test void shouldBoundEntriesWithLruEviction() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache(2, 100);
        final var brazil = new UsagePeriod(period.from(), period.to(), ZoneId.of("America/Sao_Paulo"));
        final var tokyo = new UsagePeriod(period.from(), period.to(), ZoneId.of("Asia/Tokyo"));
        sameAsFull(cache, List.of(file), period);
        sameAsFull(cache, List.of(file), brazil);
        sameAsFull(cache, List.of(file), period);
        sameAsFull(cache, List.of(file), tokyo);
        sameAsFull(cache, List.of(file), period);
        sameAsFull(cache, List.of(file), brazil);
        assertThat(cache.statistics().entries()).isEqualTo(2);
        assertThat(cache.statistics().parseAttempts()).isEqualTo(4);
        assertThat(cache.statistics().hits()).isEqualTo(2);
    }
    // Validates large day projections bypass retention without losing valid data or violating the bucket bound.
    @Test void shouldBoundRetainedDayBuckets() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache(10, 1);
        final var brazil = new UsagePeriod(period.from(), period.to(), ZoneId.of("America/Sao_Paulo"));
        sameAsFull(cache, List.of(file), brazil);
        sameAsFull(cache, List.of(file), brazil);
        assertThat(cache.statistics().entries()).isZero();
        assertThat(cache.statistics().dayBuckets()).isZero();
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
    }
    // Validates concurrent requests cannot corrupt shared entries or repeat the initial parse for one unchanged file.
    @Test void shouldSerializeConcurrentCacheReads() throws Exception {
        final var file = fixture("root.jsonl");
        final var cache = new CodexDailyObservationCache();
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> { start.await(); return cached(cache, List.of(file), period); });
            final var second = executor.submit(() -> { start.await(); return cached(cache, List.of(file), period); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(cache.statistics().parseAttempts()).isEqualTo(1);
        assertThat(cache.statistics().hits()).isEqualTo(1);
    }
    // Validates legacy strict endpoints keep their contracts while the dashboard uses the observation cache.
    @Test void shouldKeepLegacyEndpointsStrict() throws Exception {
        final var file = fixture("root.jsonl");
        final var adapter = CodexAdapter.forDirectory(directory);
        adapter.getDailyUsageReport(period);
        Files.writeString(file, header("parent"));
        assertThat(adapter.getDailyUsageReport(period).coverage().pendingSessions()).isEqualTo(1);
        assertThrows(ProviderCollectionException.class, () -> adapter.getDailyUsage(period));
        assertThrows(ProviderCollectionException.class, adapter::getUsage);
    }
}
