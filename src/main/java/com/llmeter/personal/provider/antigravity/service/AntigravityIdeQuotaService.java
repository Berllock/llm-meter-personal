package com.llmeter.personal.provider.antigravity.service;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import com.llmeter.personal.provider.antigravity.service.reader.AntigravityIdeDiscovery;
import com.llmeter.personal.provider.antigravity.service.reader.AntigravityIdeQuotaClient;
import com.llmeter.personal.provider.antigravity.service.reader.AntigravityQuotaReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coalesces concurrent polls, rediscovers peer identity and never answers a failed query with stale success. */
public final class AntigravityIdeQuotaService {
    private final Supplier<List<AntigravityIdeEndpoint>> discovery;
    private final Function<AntigravityIdeEndpoint, byte[]> query;
    private final Clock clock;
    private final boolean sharedAccount;
    private final AntigravityQuotaReader reader = new AntigravityQuotaReader();
    private AntigravityIdeEndpoint peer;
    private Instant attemptedAt;
    private boolean failed;
    private Optional<QuotaSnapshot> cached = Optional.empty();
    public AntigravityIdeQuotaService() { this(0, false); }
    public AntigravityIdeQuotaService(final long selectedPid) { this(selectedPid, false); }
    public AntigravityIdeQuotaService(final long selectedPid, final boolean sharedAccount) {
        this(new AntigravityIdeDiscovery(selectedPid)::discover, new AntigravityIdeQuotaClient()::read, Clock.systemUTC(), sharedAccount);
    }
    public AntigravityIdeQuotaService(final Supplier<List<AntigravityIdeEndpoint>> discovery,
                                     final Function<AntigravityIdeEndpoint, byte[]> query, final Clock clock) {
        this(discovery, query, clock, false);
    }
    public AntigravityIdeQuotaService(final Supplier<List<AntigravityIdeEndpoint>> discovery,
                                     final Function<AntigravityIdeEndpoint, byte[]> query, final Clock clock, final boolean sharedAccount) {
        this.discovery = discovery; this.query = query; this.clock = clock; this.sharedAccount = sharedAccount;
    }
    public synchronized Optional<QuotaSnapshot> getQuota() {
        final List<AntigravityIdeEndpoint> peers;
        try { peers = discovery.get(); }
        catch (final RuntimeException e) { clear(); throw failure(); }
        if (peers.isEmpty()) { clear(); return Optional.empty(); }
        if (peers.size() != 1 && !sharedAccount) { clear(); throw failure(); }
        final var current = peers.stream().min(AntigravityIdeEndpoint::compareTo).orElseThrow();
        final var now = clock.instant();
        if (current.samePeer(peer) && attemptedAt != null && !now.isBefore(attemptedAt)
                && Duration.between(attemptedAt, now).compareTo(Duration.ofMinutes(1)) < 0) {
            if (failed) throw failure();
            return cached;
        }
        clear(); peer = current; attemptedAt = now; failed = true;
        try {
            final var bytes = query.apply(current);
            final var verified = discovery.get();
            if (verified.isEmpty() || (verified.size() != 1 && !sharedAccount) || !current.samePeer(verified.stream().min(AntigravityIdeEndpoint::compareTo).orElseThrow())) { clear(); throw failure(); }
            final var result = reader.read(bytes, clock.instant());
            cached = result; failed = false;
            return result;
        } catch (final ProviderCollectionException e) { throw e; }
        catch (final RuntimeException e) { throw failure(); }
    }
    private void clear() { peer = null; attemptedAt = null; failed = false; cached = Optional.empty(); }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.CONFLICTING_DATA, "A single stable Antigravity IDE hub is required");
    }
}
