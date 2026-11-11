package com.llmeter.personal.provider.antigravity.service.reader;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
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
}
