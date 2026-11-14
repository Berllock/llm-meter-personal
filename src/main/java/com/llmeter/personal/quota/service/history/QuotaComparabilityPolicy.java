package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaComparability;
import com.llmeter.personal.quota.dto.QuotaComparability.AccountStatus;
import com.llmeter.personal.quota.dto.QuotaComparability.Group;
import com.llmeter.personal.quota.dto.QuotaComparability.Reason;
import com.llmeter.personal.quota.dto.QuotaComparability.Unit;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Pure, deterministic account/plan/cycle grouping. Freshness and sample sufficiency are separate future checks. */
public final class QuotaComparabilityPolicy {
    public String accountFingerprint(final QuotaSnapshot snapshot) {
        if (snapshot.provider() != ProviderType.CODEX || snapshot.accountEvidence() == null) return null;
        return QuotaHistoryCodec.hash("llm-meter:recorded-account:v1:CODEX:" + snapshot.accountEvidence().recordedAccountId());
    }

    public QuotaComparability evaluate(final String sourceContext, final QuotaSnapshot snapshot,
                                       final String fingerprint, final boolean legacy) {
        if (fingerprint != null && (snapshot.provider() != ProviderType.CODEX || !fingerprint.matches("[0-9a-f]{64}") || legacy)) {
            throw new IllegalArgumentException("Unsupported account context");
        }
        final var account = legacy ? AccountStatus.LEGACY : fingerprint == null ? AccountStatus.UNVERIFIED : AccountStatus.RECORDED_ACCOUNT;
        final var groups = new ArrayList<Group>();
        for (int i = 0; i < snapshot.windows().size(); i++) {
            final var window = snapshot.windows().get(i);
            final var reasons = reasons(account, snapshot.planType());
            if (window.limitId() == null) reasons.add(Reason.SCOPE_UNVERIFIED);
            if (window.windowMinutes() == null) reasons.add(Reason.WINDOW_DURATION_UNVERIFIED);
            final String key = reasons.isEmpty() ? QuotaHistoryCodec.hash(encoded(List.of("quota-context-v1", sourceContext,
                    snapshot.provider().name(), fingerprint, snapshot.planType(), window.limitId(), window.name(),
                    window.windowMinutes().toString(), window.resetAt().toString(), Unit.PERCENTAGE.name()))) : null;
            groups.add(new Group(Unit.PERCENTAGE, i, key, reasons));
        }
        if (snapshot.creditQuota() != null) {
            final var credits = snapshot.creditQuota();
            for (int i = 0; i < credits.allowances().size(); i++) {
                final var allowance = credits.allowances().get(i);
                final var reasons = reasons(account, snapshot.planType());
                if (allowance.kind().equals("SUBSCRIPTION")) reasons.add(credits.renewalDate() == null ? Reason.CYCLE_UNVERIFIED : Reason.RESET_DATE_ONLY);
                else {
                    reasons.add(Reason.POOL_UNIDENTIFIED);
                    reasons.add(allowance.expiresOn() == null ? Reason.CYCLE_UNVERIFIED : Reason.RESET_DATE_ONLY);
                }
                groups.add(new Group(Unit.CREDITS, i, null, reasons));
            }
        }
        return new QuotaComparability(account, groups);
    }
    private ArrayList<Reason> reasons(final AccountStatus account, final String plan) {
        final var reasons = new ArrayList<Reason>();
        if (account == AccountStatus.LEGACY) reasons.add(Reason.LEGACY_OBSERVATION);
        else if (account == AccountStatus.UNVERIFIED) reasons.add(Reason.ACCOUNT_UNVERIFIED);
        if (plan == null || plan.equalsIgnoreCase("unknown")) reasons.add(Reason.PLAN_UNVERIFIED);
        return reasons;
    }
    private String encoded(final List<String> parts) {
        final var result = new StringBuilder();
        for (final var part : parts) result.append(part.length()).append(':').append(part);
        return result.toString();
    }
}
