package com.llmeter.personal.quota.dto;

import java.time.Instant;

/** A saved observation, never a replacement for a current account query. */
public record QuotaHistoryEntry(String id, Instant savedAt, boolean accountVerified, QuotaSnapshot snapshot) {}
