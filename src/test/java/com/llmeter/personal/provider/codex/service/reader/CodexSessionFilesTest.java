package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSessionFilesTest {

    // Verifies recursive discovery, path ordering, and exclusion of unrelated files and directories.
    @Test
    void shouldFindSessionFilesRecursively(@TempDir final Path directory) throws IOException {
        final var nestedDirectory = Files.createDirectories(directory.resolve("nested/deeper"));
        final var lastSession = Files.createFile(directory.resolve("z-session.jsonl"));
        final var nestedSession = Files.createFile(nestedDirectory.resolve("session-b.jsonl"));
        final var firstSession = Files.createFile(directory.resolve("a-session.jsonl"));
        Files.createFile(directory.resolve("notes.txt"));
        Files.createFile(nestedDirectory.resolve("session.jsonl.backup"));
        Files.createDirectory(directory.resolve("not-a-file.jsonl"));
        final var discovery = new CodexSessionFiles();

        final var result = discovery.find(directory);

        assertEquals(List.of(firstSession, nestedSession, lastSession), result);
    }

    // Verifies that an empty sessions directory returns no files.
    @Test
    void shouldReturnEmptyListForEmptyDirectory(@TempDir final Path directory) {
        final var discovery = new CodexSessionFiles();

        final var result = discovery.find(directory);

        assertTrue(result.isEmpty());
    }

    // Verifies that an unavailable directory reports a collection failure with its original I/O cause.
    @Test
    void shouldPreserveCauseWhenDirectoryDoesNotExist(@TempDir final Path directory) {
        final var discovery = new CodexSessionFiles();

        final var exception = assertThrows(
                ProviderCollectionException.class,
                () -> discovery.find(directory.resolve("missing"))
        );

        assertInstanceOf(NoSuchFileException.class, exception.getCause());
    }

    // Verifies that a single file cannot be mistaken for a sessions directory.
    @Test
    void shouldRejectRegularFileAsDirectory(@TempDir final Path directory) throws IOException {
        final var file = Files.createFile(directory.resolve("session.jsonl"));
        final var discovery = new CodexSessionFiles();

        assertThrows(ProviderCollectionException.class, () -> discovery.find(file));
    }
}
