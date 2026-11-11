package com.llmeter.personal.provider.antigravity.domain;

import java.time.Instant;
import java.util.Objects;

/** Connection credentials remain in memory and are never part of a public quota DTO. */
public final class AntigravityIdeEndpoint implements Comparable<AntigravityIdeEndpoint> {
    private final long pid;
    private final Instant startedAt;
    private final int port;
    private final String token;
    public AntigravityIdeEndpoint(final long pid, final Instant startedAt, final int port, final String token) {
        if (pid < 1 || port < 1 || port > 65535 || startedAt == null || token == null
                || !token.matches("[A-Za-z0-9._-]{16,256}")) throw new IllegalArgumentException("Invalid IDE connection");
        this.pid = pid; this.startedAt = startedAt; this.port = port; this.token = token;
    }
    public int port() { return port; }
    public String token() { return token; }
    public boolean samePeer(final AntigravityIdeEndpoint other) {
        return other != null && pid == other.pid && port == other.port && startedAt.equals(other.startedAt) && token.equals(other.token);
    }
    public boolean isAlive() {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .map((final var process) -> Objects.equals(process.info().startInstant().orElse(null), startedAt)).orElse(false);
    }
    @Override public int compareTo(final AntigravityIdeEndpoint other) {
        final int started = startedAt.compareTo(other.startedAt);
        if (started != 0) return started;
        final int process = Long.compare(pid, other.pid);
        return process != 0 ? process : Integer.compare(port, other.port);
    }
    @Override public String toString() { return "AntigravityIdeEndpoint[redacted]"; }
}
