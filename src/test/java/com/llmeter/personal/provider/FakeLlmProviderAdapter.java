package com.llmeter.personal.provider;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.util.Optional;

public class FakeLlmProviderAdapter implements LlmProviderAdapter{

    private final UsageSnapshot usageSnapshot;


    public FakeLlmProviderAdapter(final UsageSnapshot usageSnapshot) {
        this.usageSnapshot = usageSnapshot;
    }

    @Override
    public ProviderType provider() {
        return usageSnapshot.provider();
    }

    @Override
        public UsageSnapshot getUsage() {
        return usageSnapshot;
    }

    @Override
        public Optional<QuotaSnapshot> getQuota() {
        return Optional.empty();
    }

}
