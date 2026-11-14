package com.llmeter.personal.quota.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import java.util.List;

public record QuotaHistoryResponse(Status status, int retentionDays, List<QuotaHistoryEntry> observations,
                                   List<ProviderType> failures) {
    public enum Status { READY, DISABLED, PARTIAL, ERROR }
    public QuotaHistoryResponse {
        observations = List.copyOf(observations);
        failures = List.copyOf(failures);
    }
}
