package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Only considers this OS user's running Antigravity hub processes; no port scanning or credential files. */
public final class AntigravityIdeDiscovery {
    private final long selectedPid;
    public AntigravityIdeDiscovery() { this(0); }
    public AntigravityIdeDiscovery(final long selectedPid) {
        if (selectedPid < 0) throw new IllegalArgumentException("Invalid IDE process selector");
        this.selectedPid = selectedPid;
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
        if (owner == null || !owner.equals(process.user()) || process.startedAt() == null
                || !validCommand(process.command())
                || !process.arguments().contains("--hub") || !process.arguments().contains("--app_data_dir=antigravity")) return Optional.empty();
        final var ports = process.arguments().stream().filter((final var arg) -> arg.startsWith("--hub-port=")).toList();
        final var tokens = process.arguments().stream().filter((final var arg) -> arg.startsWith("--csrf_token=")).toList();
        if (ports.size() != 1 || tokens.size() != 1) return Optional.empty();
        try {
            return Optional.of(new AntigravityIdeEndpoint(process.pid(), process.startedAt(),
                    Integer.parseInt(ports.getFirst().substring("--hub-port=".length())), tokens.getFirst().substring("--csrf_token=".length())));
        } catch (final IllegalArgumentException e) { return Optional.empty(); }
    }
}
