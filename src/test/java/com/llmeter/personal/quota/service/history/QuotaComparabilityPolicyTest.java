package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.QuotaAccountEvidence;
import com.llmeter.personal.quota.dto.QuotaComparability.AccountStatus;
import com.llmeter.personal.quota.dto.QuotaComparability.Reason;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.service.history.QuotaHistoryTestData.*;

class QuotaComparabilityPolicyTest {
    private final QuotaComparabilityPolicy policy = new QuotaComparabilityPolicy();
    private QuotaSnapshot identified(final String account, final String plan, final String scope, final long minutes, final long resetOffset) {
        return new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW, NOW, plan, scope,
                List.of(new QuotaWindow("primary", 20, minutes, NOW.plusSeconds(resetOffset), scope, NOW)), null, 0, null, new QuotaAccountEvidence(account));
    }
    private String group(final QuotaSnapshot snapshot) {
        return policy.evaluate(CONTEXT, snapshot, policy.accountFingerprint(snapshot), false).groups().getFirst().key();
    }
    // Validates comparable context is stable across observations but changes with account, plan, scope, duration or reset.
    @Test void shouldSeparateAccountsPlansAndCycles() {
        final var baseline = identified("synthetic-account-a", "plus", "codex", 300, 18_000);
        final String key = group(baseline);
        assertThat(key).matches("[0-9a-f]{64}");
        for (final var different : List.of(identified("synthetic-account-b", "plus", "codex", 300, 18_000),
                identified("synthetic-account-a", "pro", "codex", 300, 18_000),
                identified("synthetic-account-a", "plus", "other-model", 300, 18_000),
                identified("synthetic-account-a", "plus", "codex", 600, 18_000),
                identified("synthetic-account-a", "plus", "codex", 300, 36_000))) assertThat(group(different)).isNotEqualTo(key);
        final var later = new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW.plusSeconds(120), NOW.plusSeconds(120), "plus", "codex",
                List.of(new QuotaWindow("primary", 40, 300, NOW.plusSeconds(18_000), "codex", NOW.plusSeconds(120))), null, 0, null, baseline.accountEvidence());
        assertThat(group(later)).isEqualTo(key);
        assertThat(policy.evaluate(QuotaHistoryCodec.hash("other-folder"), baseline, policy.accountFingerprint(baseline), false).groups().getFirst().key()).isNotEqualTo(key);
    }
    // Validates account identification alone never establishes a missing plan/scope or promotes legacy observations.
    @Test void shouldExplainIncompleteAndLegacyContext() {
        final var missingPlan = identified("synthetic-account-a", null, null, 300, 18_000);
        final var partial = policy.evaluate(CONTEXT, missingPlan, policy.accountFingerprint(missingPlan), false);
        assertThat(partial.accountStatus()).isEqualTo(AccountStatus.RECORDED_ACCOUNT);
        assertThat(partial.groups().getFirst().key()).isNull();
        assertThat(partial.groups().getFirst().reasons()).containsExactly(Reason.PLAN_UNVERIFIED, Reason.SCOPE_UNVERIFIED);
        final var legacy = policy.evaluate(CONTEXT, codex(NOW, 20), null, true);
        assertThat(legacy.accountStatus()).isEqualTo(AccountStatus.LEGACY);
        assertThat(legacy.groups().getFirst().reasons()).containsExactly(Reason.LEGACY_OBSERVATION);
    }
    // Validates Kiro credit dates/pools and IDE windows cannot acquire account context from display names or folders.
    @Test void shouldKeepUnsupportedIdentitySourcesExplicitlyUnverified() {
        final var kiro = policy.evaluate(CONTEXT, kiro(), null, false);
        assertThat(kiro.accountStatus()).isEqualTo(AccountStatus.UNVERIFIED);
        assertThat(kiro.groups().getFirst().reasons()).contains(Reason.ACCOUNT_UNVERIFIED, Reason.PLAN_UNVERIFIED, Reason.RESET_DATE_ONLY);
        assertThat(kiro.groups().getLast().reasons()).contains(Reason.POOL_UNIDENTIFIED);
        assertThat(kiro.groups()).allMatch(group -> group.key() == null);
        final var ide = new QuotaSnapshot(ProviderType.ANTIGRAVITY, null, null, null, NOW, NOW, null, null,
                List.of(new QuotaWindow("weekly", 40, (Long) null, NOW.plusSeconds(3_600), "model-a", NOW, null, "Synthetic model")), "ANTIGRAVITY_IDE", 0);
        assertThat(policy.evaluate(CONTEXT, ide, null, false).groups().getFirst().reasons())
                .containsExactly(Reason.ACCOUNT_UNVERIFIED, Reason.PLAN_UNVERIFIED, Reason.WINDOW_DURATION_UNVERIFIED);
        assertThatThrownBy(() -> policy.evaluate(CONTEXT, kiro(), "a".repeat(64), false)).isInstanceOf(IllegalArgumentException.class);
    }
}
