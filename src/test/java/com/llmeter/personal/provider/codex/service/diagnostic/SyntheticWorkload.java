package com.llmeter.personal.provider.codex.service.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Owns only a generated temporary source; never receives a user's sessions path. */
final class SyntheticWorkload implements AutoCloseable {
    private final Path directory;
    private final List<Path> files = new ArrayList<>();
    private final int[] sequences;
    private String unfinished;

    SyntheticWorkload(final int count, final int events, final int pending, final int payloadBytes) throws IOException {
        if (count < 1 || count > 10_000 || events < 1 || events > 10_000 || pending < 0 || pending > count
                || payloadBytes < 0 || payloadBytes > 4096
                || (long) count * events * (payloadBytes + 600) > 256L * 1024 * 1024) {
            throw new IllegalArgumentException("Synthetic workload exceeds supported limits");
        }
        directory = Files.createTempDirectory("llm-meter-benchmark-");
        sequences = new int[count];
        try {
            final var payload = "x".repeat(payloadBytes);
            for (int i = 0; i < count; i++) {
                final var folder = directory.resolve("group-" + i % 10);
                Files.createDirectories(folder);
                final var file = folder.resolve("session-" + i + ".jsonl");
                files.add(file);
                try (final var writer = Files.newBufferedWriter(file)) {
                    writer.write("{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"synthetic-" + i
                            + "\",\"history_mode\":\"paginated\"}}\n");
                    if (i < count - pending) {
                        for (int sequence = 1; sequence <= events; sequence++) {
                            writer.write("{\"type\":\"response_item\",\"payload\":{\"text\":\"" + payload + "\"}}\n");
                            writer.write(event(sequence));
                        }
                        sequences[i] = events;
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    Path directory() {
        return directory;
    }

    void advance(final String scenario) throws IOException {
        if ("append".equals(scenario)) {
            for (int i = 0; i < files.size(); i++) {
                Files.writeString(files.get(i), event(++sequences[i]), StandardOpenOption.APPEND);
            }
        } else if ("partial-write".equals(scenario)) {
            if (unfinished == null) {
                final var next = event(sequences[0] + 1);
                final int split = next.length() / 2;
                Files.writeString(files.getFirst(), next.substring(0, split), StandardOpenOption.APPEND);
                unfinished = next.substring(split);
            } else {
                Files.writeString(files.getFirst(), unfinished, StandardOpenOption.APPEND);
                sequences[0]++;
                unfinished = null;
            }
        }
    }

    private static String event(final int sequence) {
        return "{\"ordinal\":" + (sequence * 2L) + ",\"timestamp\":\""
                + Instant.parse("2026-10-01T12:00:00Z").plusSeconds(sequence)
                + "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":"
                + counts(sequence * 10L, sequence * 3L, sequence * 2L)
                + ",\"last_token_usage\":" + counts(10, 3, 2) + "}}}\n";
    }
    private static String counts(final long input, final long output, final long cached) {
        return "{\"input_tokens\":" + input + ",\"output_tokens\":" + output + ",\"cached_input_tokens\":" + cached + "}";
    }

    @Override public void close() throws IOException {
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
