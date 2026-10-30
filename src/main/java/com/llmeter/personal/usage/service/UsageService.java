package com.llmeter.personal.usage.service;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.DailyUsageCollection;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UsageService {

    private final List<LlmProviderAdapter> adapters;

    public UsageService(final List<LlmProviderAdapter> adapters) {
        this.adapters = adapters;
    }

    public List<UsageSnapshot> getUsage() {
        return adapters.stream()
                .map(LlmProviderAdapter::getUsage)
                .toList();
    }

    public DailyUsageCollection collectDailyUsage(final UsagePeriod period) {
        return DailyUsageCollection.of(period, adapters.stream()
                .map((final var adapter) -> adapter.getDailyUsageReport(period)).toList());
    }

    public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        return adapters.stream()
                .flatMap((final var adapter) -> adapter.getDailyUsage(period).stream())
                .toList();
    }
}
