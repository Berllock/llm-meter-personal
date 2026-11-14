package com.llmeter.personal.quota.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** In-memory source evidence. Raw identifiers must never enter HTTP or archive snapshots. */
public record QuotaAccountEvidence(@JsonIgnore String recordedAccountId) {
    public QuotaAccountEvidence {
        if (recordedAccountId == null || recordedAccountId.isBlank() || recordedAccountId.length() > 512
                || recordedAccountId.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid recorded account evidence");
    }
    @Override public String toString() { return "QuotaAccountEvidence[redacted]"; }
}
