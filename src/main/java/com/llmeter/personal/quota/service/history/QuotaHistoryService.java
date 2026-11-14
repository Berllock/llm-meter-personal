package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaHistoryEntry;
import com.llmeter.personal.quota.dto.QuotaHistoryResponse;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Archives best-effort; a storage failure must never turn a valid live quota into an unavailable quota. */
public final class QuotaHistoryService {
    private final QuotaHistoryStore store;
    private final Map<ProviderType, String> contexts;
    private final Clock clock;
    private final EnumSet<ProviderType> writeFailures = EnumSet.noneOf(ProviderType.class);
    public QuotaHistoryService(final QuotaHistoryStore store, final Map<ProviderType, String> contexts, final Clock clock) {
        this.store = store; this.contexts = Map.copyOf(contexts); this.clock = clock;
    }
    public static QuotaHistoryService disabled() { return new QuotaHistoryService(new QuotaHistoryStore(null), Map.of(), Clock.systemUTC()); }

    public synchronized void record(final QuotaSnapshot snapshot) {
        if (!store.enabled() || snapshot == null || !contexts.containsKey(snapshot.provider())) return;
        try {
            store.append(contexts.get(snapshot.provider()), snapshot, clock.instant());
            writeFailures.remove(snapshot.provider());
        } catch (final IOException | RuntimeException e) { writeFailures.add(snapshot.provider()); }
    }

    public synchronized QuotaHistoryResponse read(final ProviderType provider, final int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be between 1 and 100");
        if (!store.enabled()) return new QuotaHistoryResponse(QuotaHistoryResponse.Status.DISABLED, QuotaHistoryStore.RETENTION_DAYS, List.of(), List.of());
        final var observations = new ArrayList<QuotaHistoryEntry>();
        final var failures = new ArrayList<ProviderType>();
        final var selected = contexts.keySet().stream().filter(value -> provider == null || provider == value).sorted().toList();
        int readable = 0;
        for (final var source : selected) {
            try {
                observations.addAll(store.read(source, contexts.get(source), clock.instant()));
                if (writeFailures.contains(source)) failures.add(source); else readable++;
            } catch (final IOException | RuntimeException e) { failures.add(source); }
        }
        final var status = failures.isEmpty() ? QuotaHistoryResponse.Status.READY
                : readable > 0 ? QuotaHistoryResponse.Status.PARTIAL : QuotaHistoryResponse.Status.ERROR;
        return new QuotaHistoryResponse(status, QuotaHistoryStore.RETENTION_DAYS,
                observations.stream().sorted(Comparator.comparing(QuotaHistoryEntry::savedAt).reversed().thenComparing(QuotaHistoryEntry::id)).limit(limit).toList(), failures);
    }
}
