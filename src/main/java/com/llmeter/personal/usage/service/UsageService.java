package com.llmeter.personal.usage.service;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
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



}
