package com.llmeter.personal.provider.antigravity.service.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class AntigravityIdeEnvironmentTest {
    @TempDir Path directory;
    private static final String TOKEN = "SYNTHETIC_ENV_TOKEN_1234";
    private Path environment() throws Exception {
        Files.createDirectories(directory.resolve("42"));
        return directory.resolve("42/environ");
    }
    // Validates only the exact documented key is returned; unrelated environment values and prefix-like names are ignored.
    @Test void shouldReadOnlyTheExactHubVariable() throws Exception {
        Files.writeString(environment(), "UNRELATED=SYNTHETIC_OTHER_SECRET\0NOT_ANTIGRAVITY_SERVER_CSRF_TOKEN=ignored\0"
                + "ANTIGRAVITY_SERVER_CSRF_TOKEN=" + TOKEN + "\0AFTER=ignored\0", StandardCharsets.US_ASCII);
        assertThat(new AntigravityIdeEnvironment(directory).read(42)).contains(TOKEN);
    }
    // Validates duplicate keys, malformed tokens, truncated records and missing values never produce credentials.
    @Test void shouldRejectAmbiguousOrInvalidEnvironments() throws Exception {
        final var file = environment();
        for (final var content : new String[] {
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=" + TOKEN + "\0ANTIGRAVITY_SERVER_CSRF_TOKEN=" + TOKEN + "\0",
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=short\0", "ANTIGRAVITY_SERVER_CSRF_TOKEN=\0",
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=" + "a".repeat(257) + "\0",
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=SYNTHETIC_TOKEN\nINJECTED\0",
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=SYNTHETIC_TOKEN_é\0",
                "ANTIGRAVITY_SERVER_CSRF_TOKEN=" + TOKEN, "UNRELATED=value\0", "" }) {
            Files.writeString(file, content);
            assertThat(new AntigravityIdeEnvironment(directory).read(42)).isEmpty();
        }
    }
    // Validates a missing process, invalid PID or oversized environment stays unavailable without reading arbitrary paths.
    @Test void shouldBoundReadsAndHandleUnavailableProcesses() throws Exception {
        final var reader = new AntigravityIdeEnvironment(directory);
        assertThat(reader.read(0)).isEmpty();
        assertThat(reader.read(-1)).isEmpty();
        assertThat(reader.read(42)).isEmpty();
        Files.write(environment(), new byte[AntigravityIdeEnvironment.MAX_BYTES + 1]);
        assertThat(reader.read(42)).isEmpty();
    }
    // Validates the environment file cannot redirect credential discovery through a symbolic link.
    @Test void shouldNotFollowEnvironmentSymlinks() throws Exception {
        final var file = environment();
        final var target = directory.resolve("unrelated");
        Files.writeString(target, "ANTIGRAVITY_SERVER_CSRF_TOKEN=" + TOKEN + "\0");
        Files.createSymbolicLink(file, target);
        assertThat(new AntigravityIdeEnvironment(directory).read(42)).isEmpty();
    }
}
