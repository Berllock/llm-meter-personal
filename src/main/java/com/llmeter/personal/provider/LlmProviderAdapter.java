package com.llmeter.personal.provider;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.util.List;
import java.util.Optional;

public interface LlmProviderAdapter {

    ProviderType provider();

    UsageSnapshot getUsage();

    default List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        throw new ProviderCollectionException("Daily usage is unavailable for this provider");
    }

    Optional<QuotaSnapshot> getQuota();

}
