package com.llmeter.personal.quota.service;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.ProviderQuota;
import com.llmeter.personal.quota.dto.QuotaHistoryResponse;
import com.llmeter.personal.quota.service.history.QuotaHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class QuotaService {
    private final List<LlmProviderAdapter> adapters;
    private final QuotaHistoryService history;
    public QuotaService(final List<LlmProviderAdapter> adapters) { this(adapters, QuotaHistoryService.disabled()); }
    @Autowired
    public QuotaService(final List<LlmProviderAdapter> adapters, final QuotaHistoryService history) {
        this.adapters = List.copyOf(adapters); this.history = history;
    }
    public QuotaHistoryResponse history(final ProviderType provider, final int limit) { return history.read(provider, limit); }
    public List<ProviderQuota> collect(final ProviderType provider) {
        return adapters.stream().filter((final var adapter) -> provider == null || adapter.provider() == provider)
                .map((final var adapter) -> {
                    try {
                        return adapter.getQuota().map((final var value) -> {
                                    history.record(value);
                                    return new ProviderQuota(adapter.provider(), ProviderQuota.Status.OBSERVED, value);
                                })
                                .orElseGet(() -> new ProviderQuota(adapter.provider(), ProviderQuota.Status.UNAVAILABLE, null));
                    } catch (final ProviderCollectionException e) {
                        return new ProviderQuota(adapter.provider(), ProviderQuota.Status.ERROR, null);
                    }
                }).toList();
    }
}
