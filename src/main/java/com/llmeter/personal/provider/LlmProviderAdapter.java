package com.llmeter.personal.provider;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

import java.util.Optional;

public interface LlmProviderAdapter {

    ProviderType provider();

    UsageSnapshot getUsage();

    Optional<QuotaSnapshot> getQuota();

}
