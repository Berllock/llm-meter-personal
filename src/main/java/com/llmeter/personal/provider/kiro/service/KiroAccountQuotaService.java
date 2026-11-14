package com.llmeter.personal.provider.kiro.service;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.provider.kiro.service.reader.KiroQuotaReader;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Throttles successful and failed account queries; old successes never mask a refresh failure. */
public final class KiroAccountQuotaService {
    private final Supplier<byte[]> query;
    private final Clock clock;
    private final KiroQuotaReader reader = new KiroQuotaReader();
    private Instant attemptedAt;
    private Optional<QuotaSnapshot> cached = Optional.empty();
    private boolean failed;
    public KiroAccountQuotaService(final Supplier<byte[]> query, final Clock clock) { this.query = query; this.clock = clock; }
    public synchronized Optional<QuotaSnapshot> getQuota() {
        final var now = clock.instant();
        if (attemptedAt != null && !now.isBefore(attemptedAt) && Duration.between(attemptedAt, now).compareTo(Duration.ofMinutes(1)) < 0) {
            if (failed) throw failure();
            return cached;
        }
        attemptedAt = now; cached = Optional.empty(); failed = true;
        try {
            cached = reader.read(query.get(), clock.instant()); failed = false; return cached;
        } catch (final RuntimeException e) { throw failure(); }
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.INVALID_METRICS, "Kiro account quota unavailable");
    }
}
