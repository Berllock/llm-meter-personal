package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import java.util.Objects;

/** Identifies an unavailable source without exposing exception messages, paths or session contents. */
public record ProviderUsageFailure(ProviderType provider, ProviderCollectionFailure reason) {
    public ProviderUsageFailure {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(reason);
    }
}
