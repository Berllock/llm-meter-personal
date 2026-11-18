package com.llmeter.personal.provider.antigravity.service.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Reads only the documented hub CSRF variable; callers must first verify the process identity. */
final class AntigravityIdeEnvironment {
    static final int MAX_BYTES = 256 * 1024;
    private static final byte[] KEY = "ANTIGRAVITY_SERVER_CSRF_TOKEN=".getBytes(StandardCharsets.US_ASCII);
    private final Path processes;

    AntigravityIdeEnvironment() { this(Path.of("/proc")); }
    AntigravityIdeEnvironment(final Path processes) { this.processes = processes; }

    Optional<String> read(final long pid) {
        if (pid < 1) return Optional.empty();
        byte[] bytes = null;
        try (final var input = Files.newInputStream(processes.resolve(Long.toString(pid)).resolve("environ"), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || bytes[bytes.length - 1] != 0) return Optional.empty();
            String token = null;
            int start = 0;
            for (int end = 0; end < bytes.length; end++) {
                if (bytes[end] != 0) continue;
                if (end - start >= KEY.length && Arrays.equals(bytes, start, start + KEY.length, KEY, 0, KEY.length)) {
                    if (token != null) return Optional.empty();
                    final int length = end - start - KEY.length;
                    if (length < 16 || length > 256) return Optional.empty();
                    for (int index = start + KEY.length; index < end; index++) {
                        final int value = bytes[index] & 0xff;
                        if (!(value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                                || value == '.' || value == '_' || value == '-')) return Optional.empty();
                    }
                    token = new String(bytes, start + KEY.length, length, StandardCharsets.US_ASCII);
                }
                start = end + 1;
            }
            return Optional.ofNullable(token);
        } catch (final IOException | SecurityException e) { return Optional.empty(); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
}
