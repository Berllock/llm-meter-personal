package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public class CodexSessionFiles {

    public List<Path> find(final Path directory) {
        try {
            final var attributes = Files.readAttributes(directory, BasicFileAttributes.class);
            if (!attributes.isDirectory()) {
                throw new ProviderCollectionException(
                        ProviderCollectionFailure.READ_FAILURE, "Codex sessions path must be a directory"
                );
            }

            try (final var paths = Files.walk(directory)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter((final Path path) -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .toList();
            }
        } catch (final IOException e) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.READ_FAILURE, "Could not discover Codex session files", e
            );
        } catch (final UncheckedIOException e) {
            throw new ProviderCollectionException(
                    ProviderCollectionFailure.READ_FAILURE, "Could not discover Codex session files", e.getCause()
            );
        }
    }
}
