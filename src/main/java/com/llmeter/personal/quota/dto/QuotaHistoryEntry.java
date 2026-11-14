package com.llmeter.personal.quota.dto;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A saved observation, never a replacement for a current account query. */
public record QuotaHistoryEntry(String id, Instant savedAt, boolean accountVerified, QuotaSnapshot snapshot,
                                @JsonInclude(JsonInclude.Include.NON_NULL) QuotaComparability comparability) {
    public QuotaHistoryEntry(final String id, final Instant savedAt, final boolean accountVerified, final QuotaSnapshot snapshot) {
        this(id, savedAt, accountVerified, snapshot, null);
    }
}
