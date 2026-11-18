package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Only considers this OS user's running Antigravity hub processes; no port scanning or login stores. */
public final class AntigravityIdeDiscovery {
    private final long selectedPid;
    private final Function<Long, Optional<String>> environment;
    private final Predicate<Candidate> currentProcess;
    public AntigravityIdeDiscovery() { this(0); }
    public AntigravityIdeDiscovery(final long selectedPid) {
        this(selectedPid, new AntigravityIdeEnvironment()::read, AntigravityIdeDiscovery::isCurrent);
    }
    AntigravityIdeDiscovery(final long selectedPid, final Function<Long, Optional<String>> environment,
                           final Predicate<Candidate> currentProcess) {
        if (selectedPid < 0) throw new IllegalArgumentException("Invalid IDE process selector");
        this.selectedPid = selectedPid;
        this.environment = environment;
        this.currentProcess = currentProcess;
    }
    public record Candidate(String user, String command, List<String> arguments, long pid, Instant startedAt) {
        public Candidate { arguments = List.copyOf(arguments); }
        @Override public String toString() { return "AntigravityIdeCandidate[redacted]"; }
    }
    public List<AntigravityIdeEndpoint> discover() {
        final String owner = System.getProperty("user.name");
        try (final var processes = selectedPid == 0 ? ProcessHandle.allProcesses() : ProcessHandle.of(selectedPid).stream()) {
            return processes.filter(ProcessHandle::isAlive).map((final var process) -> {
                final var info = process.info();
                return candidate(new Candidate(info.user().orElse(null), info.command().orElse(""),
                        List.of(info.arguments().orElse(new String[0])), process.pid(), info.startInstant().orElse(null)), owner);
            }).flatMap(Optional::stream).toList();
        }
    }
    private boolean validCommand(final String command) {
        try {
            final var filename = Path.of(command.isBlank() ? "." : command).getFileName();
            return filename != null && "agy".equals(filename.toString());
        } catch (final IllegalArgumentException e) { return false; }
    }
    public Optional<AntigravityIdeEndpoint> candidate(final Candidate process, final String owner) {
        if (owner == null || !owner.equals(process.user()) || process.startedAt() == null || process.pid() < 1
                || !validCommand(process.command())
                || !process.arguments().contains("--hub") || !process.arguments().contains("--app_data_dir=antigravity")) return Optional.empty();
        final var ports = process.arguments().stream().filter((final var arg) -> arg.startsWith("--hub-port=")).toList();
        final var tokens = process.arguments().stream().filter((final var arg) -> arg.startsWith("--csrf_token=")).toList();
        if (ports.size() != 1 || tokens.size() > 1) return Optional.empty();
        try {
            final int port = Integer.parseInt(ports.getFirst().substring("--hub-port=".length()));
            if (port < 1 || port > 65535) return Optional.empty();
            final String token;
            if (tokens.size() == 1) token = tokens.getFirst().substring("--csrf_token=".length());
            else {
                // The environment belongs to this exact live process, not the meter or another IDE instance.
                if (!currentProcess.test(process)) return Optional.empty();
                final var value = environment.apply(process.pid());
                if (value.isEmpty() || !currentProcess.test(process)) return Optional.empty();
                token = value.orElseThrow();
            }
            return Optional.of(new AntigravityIdeEndpoint(process.pid(), process.startedAt(),
                    port, token));
        } catch (final IllegalArgumentException e) { return Optional.empty(); }
    }
    private static boolean isCurrent(final Candidate expected) {
        return ProcessHandle.of(expected.pid()).filter(ProcessHandle::isAlive).map((final var process) -> {
            final var info = process.info();
            return expected.equals(new Candidate(info.user().orElse(null), info.command().orElse(""),
                    List.of(info.arguments().orElse(new String[0])), process.pid(), info.startInstant().orElse(null)));
        }).orElse(false);
    }
}
