package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.CreditQuota;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class QuotaHistoryTestData {
    public static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    public static final String CONTEXT = QuotaHistoryCodec.hash("synthetic-source");
    private QuotaHistoryTestData() {}
    public static QuotaSnapshot codex(final Instant time, final double used) {
        return new QuotaSnapshot(ProviderType.CODEX, null, null, null, time, time, "plus", "codex",
                List.of(new QuotaWindow("primary", used, 300, NOW.plusSeconds(18_000), "codex", time)));
    }
    public static QuotaSnapshot kiro() {
        return new QuotaSnapshot(ProviderType.KIRO, null, null, null, NOW, NOW, null, null, List.of(), "KIRO_CLI", 0,
                new CreditQuota("Synthetic Free", LocalDate.parse("2026-12-01"), false, List.of(
                        new CreditQuota.CreditAllowance("SUBSCRIPTION", "Plan credits", "12.123456789012345678", "50", "37.876543210987654322", null, null),
                        new CreditQuota.CreditAllowance("ADD_ON", "Add-on credits", "0.25", "2", "1.75", LocalDate.parse("2026-12-20"), null))));
    }
}
