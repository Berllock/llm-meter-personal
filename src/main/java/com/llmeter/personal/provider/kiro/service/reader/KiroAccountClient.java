package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One short-lived ACP connection; never creates a session, sends a prompt or grants tool access. */
public final class KiroAccountClient {
    private final Path executable;
    private final long timeoutMillis;
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public KiroAccountClient(final Path executable) { this(executable, 12_000); }
    public KiroAccountClient(final Path executable, final long timeoutMillis) {
        if (!executable.isAbsolute() || timeoutMillis < 1) throw new IllegalArgumentException("An absolute Kiro executable path and positive timeout are required");
        this.executable = executable; this.timeoutMillis = timeoutMillis;
    }
    public byte[] read() {
        Process process = null;
        Path directory = null;
        Thread worker = null;
        try {
            directory = Files.createTempDirectory("llmeter-kiro-account-");
            process = new ProcessBuilder(executable.toString(), "acp", "--agent-engine", "v3", "--auth-method", "cli")
                    .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final var connection = process;
            final var result = new CompletableFuture<byte[]>();
            worker = Thread.startVirtualThread(() -> {
                try { result.complete(exchange(connection)); }
                catch (final Exception e) { result.completeExceptionally(failure()); }
            });
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (final Exception e) { throw failure(); }
        finally {
            if (process != null) {
                final var children = process.descendants().toList();
                children.forEach(ProcessHandle::destroy);
                process.destroy();
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (worker != null) worker.interrupt();
            if (directory != null) {
                // The account-only connection should leave the temporary working directory empty.
                try (final var files = Files.walk(directory)) {
                    for (final var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (final IOException ignored) { /* Never expose runtime diagnostics or account data. */ }
            }
        }
    }
    private byte[] exchange(final Process process) throws IOException {
        try (final var input = new BufferedInputStream(process.getInputStream()); final var output = process.getOutputStream()) {
            send(output, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                    "protocolVersion", 1, "clientCapabilities", Map.of(), "clientInfo", Map.of("name", "llm-meter", "version", "0.1.0"))));
            int expected = 1;
            int total = 0;
            final var line = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1) {
                if (++total > 2 * 1024 * 1024) throw failure();
                if (next != '\n') { line.write(next); continue; }
                final JsonNode message = mapper.readTree(line.toByteArray());
                line.reset();
                if (message == null || !"2.0".equals(message.path("jsonrpc").asString())) throw failure();
                if (message.has("method")) {
                    if (message.has("id")) send(output, Map.of("jsonrpc", "2.0", "id", message.get("id"),
                            "error", Map.of("code", -32601, "message", "Client operation unavailable")));
                    continue;
                }
                if (!message.path("id").isIntegralNumber() || message.path("id").intValue() != expected
                        || message.has("error") || !message.has("result")) throw failure();
                if (expected == 2) return mapper.writeValueAsBytes(message.get("result"));
                if (message.path("result").path("protocolVersion").intValue() != 1) throw failure();
                expected = 2;
                send(output, Map.of("jsonrpc", "2.0", "id", 2, "method", "_kiro/account/getUsage", "params", Map.of()));
            }
            throw failure();
        }
    }
    private void send(final OutputStream output, final Object message) throws IOException {
        output.write(mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8));
        output.write('\n'); output.flush();
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.INVALID_METRICS, "Kiro account query unavailable");
    }
}
