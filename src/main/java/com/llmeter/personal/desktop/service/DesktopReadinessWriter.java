package com.llmeter.personal.desktop.service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DesktopReadinessWriter {

    public void publish(final Path destination, final int port, final String instanceId) {
        if (port <= 0 || port > 65535 || instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("Invalid desktop readiness information");
        }
        try {
            final var absolute = destination.toAbsolutePath();
            final var temporary = Files.createTempFile(absolute.getParent(), "readiness-", ".tmp");
            try {
                Files.writeString(temporary, new JsonMapper().writeValueAsString(new Readiness(port, instanceId)));
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Could not publish desktop readiness", e);
        }
    }

    private record Readiness(int port, String instanceId) {
    }
}
