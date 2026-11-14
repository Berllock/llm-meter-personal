package com.llmeter.personal.quota.dto;

import java.time.LocalDate;
import java.util.List;

/** Account credits are independent of the local session subtotal and token-based windows. */
public record CreditQuota(String planName, LocalDate renewalDate, boolean overagesEnabled,
                          List<CreditAllowance> allowances) {
    public CreditQuota { allowances = List.copyOf(allowances); }
    public record CreditAllowance(String kind, String name, String used, String limit, String remaining,
                                  LocalDate expiresOn, Integer daysUntilExpiry) {}
}
