package com.llmeter.personal.provider.antigravity.service.reader;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AntigravityIdeDiscoveryTest {
    private final AntigravityIdeDiscovery discovery = new AntigravityIdeDiscovery();
    private final List<String> args = List.of("--hub", "--app_data_dir=antigravity", "--hub-port=54321", "--csrf_token=PRIVATE_SYNTHETIC_TOKEN");
    private AntigravityIdeDiscovery.Candidate candidate(final String user, final String command, final List<String> args) {
        return new AntigravityIdeDiscovery.Candidate(user, command, args, 42, Instant.EPOCH);
    }
    // Validates only explicit same-user agy hubs qualify and diagnostic strings never contain connection credentials.
    @Test void shouldDiscoverOnlyQualifiedPeers() {
        final var source = candidate("owner", "/synthetic/agy", args);
        final var peer = discovery.candidate(source, "owner").orElseThrow();
        assertThat(peer.port()).isEqualTo(54321);
        assertThat(peer.toString()).doesNotContain("PRIVATE_SYNTHETIC_TOKEN");
        assertThat(source.toString()).doesNotContain("PRIVATE_SYNTHETIC_TOKEN");
        assertThat(discovery.candidate(candidate("another", "/synthetic/agy", args), "owner")).isEmpty();
        assertThat(discovery.candidate(candidate("owner", "/synthetic/other", args), "owner")).isEmpty();
        assertThat(discovery.candidate(candidate("owner", "/", args), "owner")).isEmpty();
        assertThat(discovery.candidate(candidate("owner", "/synthetic/agy", args.subList(1,args.size())), "owner")).isEmpty();
    }
    // Validates missing, malformed and duplicated port/token flags cannot create arbitrary network targets.
    @Test void shouldRejectInvalidEndpoints() {
        for (final var flags : List.of(
                List.of("--hub","--app_data_dir=antigravity","--hub-port=65536","--csrf_token=PRIVATE_SYNTHETIC_TOKEN"),
                List.of("--hub","--app_data_dir=antigravity","--hub-port=1234","--csrf_token=short"),
                List.of("--hub","--app_data_dir=antigravity","--hub-port=1234","--hub-port=4321","--csrf_token=PRIVATE_SYNTHETIC_TOKEN"))) {
            assertThat(discovery.candidate(candidate("owner", "/synthetic/agy", flags), "owner")).isEmpty();
        }
    }
    // Validates current IDE hubs can supply the same authenticated endpoint through their own documented environment variable.
    @Test void shouldDiscoverEnvironmentAuthenticatedHub() {
        final var reads = new AtomicInteger();
        final var currentChecks = new AtomicInteger();
        final var reader = new AntigravityIdeDiscovery(0, (final var pid) -> {
            assertThat(pid).isEqualTo(42); reads.incrementAndGet(); return Optional.of("SYNTHETIC_ENV_TOKEN_1234");
        }, (final var process) -> { currentChecks.incrementAndGet(); return true; });
        final var value = reader.candidate(candidate("owner", "/synthetic/agy", args.subList(0, 3)), "owner").orElseThrow();
        assertThat(value.port()).isEqualTo(54321);
        assertThat(value.token()).isEqualTo("SYNTHETIC_ENV_TOKEN_1234");
        assertThat(value.toString()).doesNotContain("SYNTHETIC_ENV_TOKEN_1234");
        assertThat(reads.get()).isEqualTo(1);
        assertThat(currentChecks.get()).isEqualTo(2);
    }
    // Validates unrelated users/commands and invalid ports or explicit credentials never trigger an environment fallback.
    @Test void shouldVerifySignatureBeforeReadingEnvironment() {
        final var reader = new AntigravityIdeDiscovery(0, (final var pid) -> { throw new AssertionError("Must not read environment"); }, (final var process) -> true);
        assertThat(reader.candidate(candidate("other", "/synthetic/agy", args.subList(0, 3)), "owner")).isEmpty();
        assertThat(reader.candidate(candidate("owner", "/synthetic/other", args.subList(0, 3)), "owner")).isEmpty();
        for (final var flags : List.of(
                List.of("--hub", "--hub-port=54321"),
                List.of("--hub", "--app_data_dir=antigravity", "--hub-port=70000"),
                List.of("--hub", "--app_data_dir=antigravity", "--hub-port=54321", "--hub-port=54322"),
                List.of("--hub", "--app_data_dir=antigravity", "--hub-port=54321", "--csrf_token=short"),
                List.of("--hub", "--app_data_dir=antigravity", "--hub-port=54321", "--csrf_token=ONE", "--csrf_token=TWO"))) {
            assertThat(reader.candidate(candidate("owner", "/synthetic/agy", flags), "owner")).isEmpty();
        }
        assertThat(reader.candidate(candidate("owner", "/synthetic/agy", args), "owner")).isPresent();
    }
    // Validates disappearing or replaced processes cannot donate a token to another connection before or after the environment read.
    @Test void shouldRejectProcessChangesAroundEnvironmentRead() {
        final var reads = new AtomicInteger();
        final var missing = new AntigravityIdeDiscovery(0, (final var pid) -> { reads.incrementAndGet(); return Optional.of("SYNTHETIC_ENV_TOKEN_1234"); }, (final var process) -> false);
        assertThat(missing.candidate(candidate("owner", "/synthetic/agy", args.subList(0, 3)), "owner")).isEmpty();
        assertThat(reads.get()).isZero();
        final var checks = new AtomicInteger();
        final var replaced = new AntigravityIdeDiscovery(0, (final var pid) -> { reads.incrementAndGet(); return Optional.of("SYNTHETIC_ENV_TOKEN_1234"); }, (final var process) -> checks.incrementAndGet() == 1);
        assertThat(replaced.candidate(candidate("owner", "/synthetic/agy", args.subList(0, 3)), "owner")).isEmpty();
        assertThat(reads.get()).isEqualTo(1);
    }
    // Validates inaccessible and malformed environment credentials remain unavailable without weakening endpoint validation.
    @Test void shouldRequireValidEnvironmentEvidence() {
        for (final var token : List.of(Optional.<String>empty(), Optional.of("short"), Optional.of("SYNTHETIC_BAD_TOKEN\n"))) {
            final var reader = new AntigravityIdeDiscovery(0, (final var pid) -> token, (final var process) -> true);
            assertThat(reader.candidate(candidate("owner", "/synthetic/agy", args.subList(0, 3)), "owner")).isEmpty();
        }
    }
}
