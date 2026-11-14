package com.llmeter.personal.quota.dto;

import java.util.List;

/** Context identification is not a forecast or proof of the currently signed-in account. */
public record QuotaComparability(AccountStatus accountStatus, List<Group> groups) {
    public enum AccountStatus { RECORDED_ACCOUNT, UNVERIFIED, LEGACY }
    public enum Unit { PERCENTAGE, CREDITS }
    public enum Reason { ACCOUNT_UNVERIFIED, LEGACY_OBSERVATION, PLAN_UNVERIFIED, SCOPE_UNVERIFIED,
        WINDOW_DURATION_UNVERIFIED, RESET_DATE_ONLY, CYCLE_UNVERIFIED, POOL_UNIDENTIFIED }
    public record Group(Unit unit, int index, String key, List<Reason> reasons) {
        public Group { reasons = List.copyOf(reasons); }
    }
    public QuotaComparability { groups = List.copyOf(groups); }
}
