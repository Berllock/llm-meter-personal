package com.llmeter.personal.usage.service;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.DailyUsageCollection;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service
public class UsageService {

    private final List<LlmProviderAdapter> adapters;

    public UsageService(final List<LlmProviderAdapter> adapters) {
        this.adapters = adapters;
    }

    public List<UsageSnapshot> getUsage() {
        return getUsage(null);
    }

    public List<UsageSnapshot> getUsage(final ProviderType provider) {
        return selected(provider)
                .map(LlmProviderAdapter::getUsage)
                .toList();
    }

    public DailyUsageCollection collectDailyUsage(final UsagePeriod period) {
        return collectDailyUsage(period, null);
    }

    public DailyUsageCollection collectDailyUsage(final UsagePeriod period, final ProviderType provider) {
        return DailyUsageCollection.of(period, selected(provider)
                .map((final var adapter) -> adapter.getDailyUsageReport(period)).toList());
    }

    public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        return getDailyUsage(period, null);
    }

    public List<DailyUsage> getDailyUsage(final UsagePeriod period, final ProviderType provider) {
        return selected(provider)
                .flatMap((final var adapter) -> adapter.getDailyUsage(period).stream())
                .toList();
    }

    private Stream<LlmProviderAdapter> selected(final ProviderType provider) {
        return adapters.stream().filter((final var adapter) -> provider == null || adapter.provider() == provider);
    }
}
