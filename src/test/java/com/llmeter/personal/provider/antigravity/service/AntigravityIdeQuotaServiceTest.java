package com.llmeter.personal.provider.antigravity.service;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AntigravityIdeQuotaServiceTest {
    private static final class Time extends Clock {
        private Instant now = Instant.parse("2026-11-10T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private AntigravityIdeEndpoint peer(final int port) { return new AntigravityIdeEndpoint(42, Instant.EPOCH, port,"PRIVATE_SYNTHETIC_TOKEN"); }
    private byte[] response() { return "{\"response\":{\"groups\":[{\"buckets\":[{\"bucketId\":\"synthetic\",\"window\":\"5h\",\"remainingFraction\":0.6,\"resetTime\":\"2026-11-10T15:00:00Z\"}]}]}}".getBytes(StandardCharsets.UTF_8); }
    // Validates polling reuses one timestamped query for a minute and a failed retry never falls back to earlier success.
    @Test void shouldBoundQueriesAndInvalidateFailures() {
        final var time = new Time();
        final var calls = new AtomicInteger();
        final var service = new AntigravityIdeQuotaService(() -> List.of(peer(54321)), endpoint -> { if (calls.incrementAndGet()>1) throw new IllegalStateException("PRIVATE_FAILURE"); return response(); }, time);
        final var first = service.getQuota().orElseThrow();
        time.now = time.now.plusSeconds(59);
        assertThat(service.getQuota().orElseThrow()).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);
        time.now = time.now.plusSeconds(1);
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class).hasMessageNotContaining("PRIVATE_FAILURE");
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
    // Validates no/ambiguous hubs do not query and changing peer identity starts a fresh query instead of mixing sources.
    @Test void shouldKeepPeerIdentityIsolated() {
        final var peers = new AtomicReference<List<AntigravityIdeEndpoint>>(List.of());
        final var calls = new AtomicInteger();
        final var service = new AntigravityIdeQuotaService(peers::get, endpoint -> { calls.incrementAndGet(); return response(); },new Time());
        assertThat(service.getQuota()).isEmpty();
        peers.set(List.of(peer(54321), peer(54322)));
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class);
        assertThat(calls.get()).isZero();
        peers.set(List.of(peer(54321))); service.getQuota();
        peers.set(List.of(peer(54322))); service.getQuota();
        assertThat(calls.get()).isEqualTo(2);
        peers.set(List.of()); assertThat(service.getQuota()).isEmpty();
    }
    // Validates explicit same-account consent chooses one stable peer, never sums windows and survives enumeration reordering.
    @Test void shouldSelectOnePeerOnlyWithSharedAccountConsent() {
        final var peers = new AtomicReference<List<AntigravityIdeEndpoint>>(List.of(peer(54322), peer(54321)));
        final var calls = new AtomicInteger();
        final var port = new AtomicInteger();
        final var service = new AntigravityIdeQuotaService(peers::get, endpoint -> { calls.incrementAndGet(); port.set(endpoint.port()); return response(); }, new Time(), true);
        final var first = service.getQuota().orElseThrow();
        assertThat(port.get()).isEqualTo(54321);
        assertThat(first.windows()).hasSize(1);
        peers.set(List.of(peer(54321), peer(54322)));
        assertThat(service.getQuota().orElseThrow()).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);
        peers.set(List.of(peer(54322)));
        service.getQuota();
        assertThat(port.get()).isEqualTo(54322);
        assertThat(calls.get()).isEqualTo(2);
    }
    // Validates a peer that disappears during a network request cannot supply an accepted snapshot.
    @Test void shouldRejectPeerChangeDuringRead() {
        final var peers = new AtomicReference<List<AntigravityIdeEndpoint>>(List.of(peer(54321)));
        final var service = new AntigravityIdeQuotaService(peers::get, endpoint -> { peers.set(List.of()); return response(); },new Time());
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class);
    }
}
