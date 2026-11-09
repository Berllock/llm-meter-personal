package com.llmeter.personal.provider.codex.service.cache;

import com.llmeter.personal.provider.codex.dto.CodexDailyObservation;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Bounded parsed observations, validated against full current content before every reuse. */
public final class CodexDailyObservationCache {
    public static final int DEFAULT_MAX_ENTRIES = 512;
    public static final int DEFAULT_MAX_DAY_BUCKETS = 32_768;

    private final int maxEntries;
    private final int maxDayBuckets;
    private final StreamOpener opener;
    private final CodexSessionReader reader = new CodexSessionReader();
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final byte[] buffer = new byte[64 * 1024];
    private int dayBuckets;
    private long completedFiles;
    private long hashedBytes;
    private long hits;
    private long parseAttempts;

    public CodexDailyObservationCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_DAY_BUCKETS);
    }

    public CodexDailyObservationCache(final int maxEntries, final int maxDayBuckets) {
        this(maxEntries, maxDayBuckets, Files::newInputStream);
    }

    CodexDailyObservationCache(final int maxEntries, final int maxDayBuckets, final StreamOpener opener) {
        if (maxEntries < 1 || maxDayBuckets < 1) {
            throw new IllegalArgumentException("Cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxDayBuckets = maxDayBuckets;
        this.opener = opener;
    }

    @FunctionalInterface
    interface StreamOpener {
        InputStream open(Path file) throws IOException;
    }

    public record Statistics(long completedFiles, long hashedBytes, long hits, long parseAttempts,
                             int entries, int dayBuckets) { }
    private record Key(Path file, ZoneId zone) { }
    private record Entry(byte[] digest, State state, CodexDailyObservation observation, int buckets) { }
    private record State(long bytes, FileTime modified, Object fileKey) { }
    private record Content(byte[] digest, long bytes) { }

    /** Serializes cache work per source; failures never return an earlier observation. */
    public synchronized List<CodexDailyObservation> readAll(final List<Path> files, final ZoneId zone) {
        final var normalized = files.stream().map((final var file) -> file.toAbsolutePath().normalize()).toList();
        prune(new HashSet<>(normalized));
        final var observations = new ArrayList<CodexDailyObservation>(files.size());
        for (final var file : normalized) {
            try {
                observations.add(read(file, zone));
                completedFiles++;
            } catch (final IOException e) {
                invalidate(file);
                throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE,
                        "Could not validate the Codex session", e);
            } catch (final RuntimeException e) {
                invalidate(file);
                throw e;
            }
        }
        return List.copyOf(observations);
    }

    public synchronized Statistics statistics() {
        return new Statistics(completedFiles, hashedBytes, hits, parseAttempts, entries.size(), dayBuckets);
    }

    /** Releases observations without resetting diagnostic counters. */
    public synchronized void clear() {
        entries.clear();
        dayBuckets = 0;
    }

    private CodexDailyObservation read(final Path file, final ZoneId zone) throws IOException {
        final var key = new Key(file, zone);
        final var beforeValidation = state(file);
        final var previous = entries.get(key);
        // Metadata may prove that a fresh parse is needed; it can never prove a cache hit.
        if (previous != null && beforeValidation.equals(previous.state())) {
            final var content = digest(file);
            final var afterValidation = state(file);
            if (beforeValidation.equals(afterValidation) && content.bytes() == afterValidation.bytes()
                    && Arrays.equals(previous.digest(), content.digest())) {
                hits++;
                return previous.observation();
            }
        }
        remove(key);
        // A miss parses and hashes the same byte stream; no transcript or byte array is retained.
        final var beforeParse = state(file);
        final var digest = sha256();
        parseAttempts++;
        try (final var counted = new CountingInputStream(opener.open(file));
             final var source = new BufferedReader(new InputStreamReader(new DigestInputStream(counted, digest),
                     StandardCharsets.UTF_8.newDecoder()))) {
            try {
                final var observation = reader.readDailyObservation(source, zone);
                final var afterParse = state(file);
                if (beforeParse.equals(afterParse) && counted.bytes == afterParse.bytes()) {
                    retain(key, new Entry(digest.digest(), afterParse, observation,
                            observation.dailyUsage() == null ? 0 : Math.max(observation.dailyUsage().days().size(), observation.dailyUsage().modelDays().size())));
                }
                // A valid stream observed during a change has normal reader semantics, but is not cached.
                return observation;
            } finally {
                hashedBytes += counted.bytes;
            }
        }
    }

    private Content digest(final Path file) throws IOException {
        final var digest = sha256();
        long bytes = 0;
        try (final var source = opener.open(file)) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytes += count;
                hashedBytes += count;
            }
        }
        return new Content(digest.digest(), bytes);
    }

    private static State state(final Path file) throws IOException {
        final var attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("A regular session file is required");
        }
        return new State(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private void retain(final Key key, final Entry entry) {
        if (entry.buckets() > maxDayBuckets) {
            return;
        }
        entries.put(key, entry);
        dayBuckets += entry.buckets();
        while (entries.size() > maxEntries || dayBuckets > maxDayBuckets) {
            final var iterator = entries.entrySet().iterator();
            dayBuckets -= iterator.next().getValue().buckets();
            iterator.remove();
        }
    }

    private void remove(final Key key) {
        final var removed = entries.remove(key);
        if (removed != null) {
            dayBuckets -= removed.buckets();
        }
    }

    private void prune(final Set<Path> files) {
        final var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            if (!files.contains(entry.getKey().file())) {
                dayBuckets -= entry.getValue().buckets();
                iterator.remove();
            }
        }
    }

    private void invalidate(final Path file) {
        final var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            if (entry.getKey().file().equals(file)) {
                dayBuckets -= entry.getValue().buckets();
                iterator.remove();
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytes;
        private CountingInputStream(final InputStream input) {
            super(input);
        }
        @Override public int read() throws IOException {
            final int value = in.read();
            if (value >= 0) {
                bytes++;
            }
            return value;
        }
        @Override public int read(final byte[] destination, final int offset, final int length) throws IOException {
            final int count = in.read(destination, offset, length);
            if (count > 0) {
                bytes += count;
            }
            return count;
        }
    }
}
