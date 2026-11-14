package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaHistoryEntry;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Versioned, bounded per-provider files. Corrupt/unsupported files are never replaced with empty history. */
public final class QuotaHistoryStore {
    public static final int RETENTION_DAYS = 30;
    static final int MAX_ENTRIES = 5_000;
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path directory;
    private final int maxEntries;
    private final int maxBytes;
    private final QuotaHistoryCodec codec = new QuotaHistoryCodec();
    private record Stored(String context, QuotaHistoryEntry observation) {}
    private record Document(int version, List<Stored> observations) {}

    public QuotaHistoryStore(final Path directory) {
        this(directory, MAX_ENTRIES, MAX_BYTES);
    }
    QuotaHistoryStore(final Path directory, final int maxEntries, final int maxBytes) {
        if (directory != null && !directory.isAbsolute()) throw new IllegalArgumentException("History directory must be absolute");
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("History bounds must be positive");
        this.directory = directory; this.maxEntries = maxEntries; this.maxBytes = maxBytes;
    }
    public boolean enabled() { return directory != null; }

    public synchronized void append(final String context, final QuotaSnapshot value, final Instant savedAt) throws IOException {
        if (!enabled()) return;
        if (context == null || !context.matches("[0-9a-f]{64}") || savedAt == null) throw new IllegalArgumentException("Invalid history context");
        final var snapshot = codec.reduced(value);
        final var id = codec.identity(context, snapshot);
        prepareDirectory();
        final var lockPath = path(snapshot.provider(), ".lock");
        try { createPrivateFile(lockPath); } catch (final FileAlreadyExistsException ignored) { /* Reuse the provider lock. */ }
        regular(lockPath);
        try (final var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             final var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("History is busy");
            final var loaded = load(snapshot.provider());
            final var existing = retained(loaded, savedAt);
            final boolean known = existing.stream().anyMatch(entry -> entry.observation().id().equals(id));
            if (known && existing.size() == loaded.size()) return;
            final var entries = new ArrayList<>(existing);
            if (!known) entries.add(new Stored(context, new QuotaHistoryEntry(id, savedAt, false, snapshot)));
            entries.sort(Comparator.comparing(entry -> entry.observation().savedAt()));
            final int first = Math.max(0, entries.size() - maxEntries);
            final var bounded = List.copyOf(entries.subList(first, entries.size()));
            final byte[] bytes = encodeBounded(bounded);
            final var temporary = Files.createTempFile(directory, ".quota-", ".tmp");
            try {
                privatePermissions(temporary);
                try (final var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    final var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, path(snapshot.provider(), ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    public synchronized List<QuotaHistoryEntry> read(final ProviderType provider, final String context, final Instant now) throws IOException {
        if (!enabled()) return List.of();
        return retained(load(provider), now).stream().filter(entry -> entry.context().equals(context)).map(Stored::observation).toList();
    }

    private List<Stored> load(final ProviderType provider) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("History unavailable");
        final var file = path(provider, ".json");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        regular(file);
        if (Files.size(file) > maxBytes) throw new IOException("History exceeds its bound");
        try (final var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final var bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("History exceeds its bound");
            final var tree = codec.mapper.readTree(bytes);
            for (final var entry : tree.path("observations")) {
                final var snapshot = entry.path("observation").path("snapshot");
                if (!(snapshot instanceof ObjectNode object)) throw new IOException("Invalid history observation");
                // QuotaSnapshot omits its zero-valued count; all other required primitive fields remain strict.
                if (!object.has("excludedWindows")) object.put("excludedWindows", 0);
            }
            final var document = codec.mapper.treeToValue(tree, Document.class);
            if (document.version() != 1 || document.observations() == null || document.observations().size() > maxEntries) throw new IOException("Unsupported history");
            final var ids = new HashSet<String>();
            for (final var entry : document.observations()) {
                if (entry == null || entry.context() == null || !entry.context().matches("[0-9a-f]{64}")) throw new IOException("Invalid history context");
                final var observation = entry.observation();
                if (observation == null || observation.savedAt() == null || observation.accountVerified()
                        || observation.snapshot().provider() != provider || !codec.reduced(observation.snapshot()).equals(observation.snapshot())
                        || !codec.identity(entry.context(), observation.snapshot()).equals(observation.id()) || !ids.add(observation.id())) throw new IOException("Invalid history observation");
            }
            return document.observations();
        } catch (final RuntimeException e) { throw new IOException("History could not be read safely"); }
    }
    private List<Stored> retained(final List<Stored> entries, final Instant now) {
        final var cutoff = now.minus(Duration.ofDays(RETENTION_DAYS));
        return entries.stream().filter(entry -> !entry.observation().savedAt().isBefore(cutoff)).toList();
    }
    private byte[] encodeBounded(final List<Stored> entries) throws IOException {
        // Binary search the oldest removable prefix rather than repeatedly serializing one deletion at a time.
        int start = 0;
        int end = entries.size() - 1;
        byte[] result = codec.mapper.writeValueAsBytes(new Document(1, entries));
        if (result.length <= maxBytes) return result;
        while (start < end) {
            final int middle = (start + end) / 2;
            final var candidate = codec.mapper.writeValueAsBytes(new Document(1, entries.subList(middle, entries.size())));
            if (candidate.length <= maxBytes) end = middle; else start = middle + 1;
        }
        result = codec.mapper.writeValueAsBytes(new Document(1, entries.subList(start, entries.size())));
        if (result.length > maxBytes) throw new IOException("Observation exceeds history bound");
        return result;
    }
    private Path path(final ProviderType provider, final String suffix) { return directory.resolve(provider.name().toLowerCase(Locale.ROOT) + suffix); }
    private void prepareDirectory() throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("History unavailable");
    }
    private void createPrivateFile(final Path file) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(file);
    }
    private void privatePermissions(final Path file) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    private void regular(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A regular history file is required");
    }
}
