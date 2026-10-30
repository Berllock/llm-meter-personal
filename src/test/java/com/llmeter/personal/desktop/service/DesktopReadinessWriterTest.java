package com.llmeter.personal.desktop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopReadinessWriterTest {

    // Verifies the atomic readiness payload contains only the actual port and launch identity.
    @Test
    void shouldPublishReadinessWithoutCredentials(@TempDir final Path directory) throws Exception {
        final var file = directory.resolve("ready.json");
        new DesktopReadinessWriter().publish(file, 12345, "synthetic-instance");
        final var content = new JsonMapper().readTree(Files.readString(file));
        assertThat(content.path("port").intValue()).isEqualTo(12345);
        assertThat(content.path("instanceId").stringValue()).isEqualTo("synthetic-instance");
        assertThat(content.size()).isEqualTo(2);
        try (final var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(file);
        }
    }

    // Verifies that an unbound port or absent launch identity cannot announce a ready backend.
    @Test
    void shouldRejectInvalidReadiness(@TempDir final Path directory) {
        final var writer = new DesktopReadinessWriter();
        final var file = directory.resolve("ready.json");
        assertThrows(IllegalArgumentException.class, () -> writer.publish(file, 0, "instance"));
        assertThrows(IllegalArgumentException.class, () -> writer.publish(file, 8080, ""));
        assertThat(Files.exists(file)).isFalse();
    }
}
