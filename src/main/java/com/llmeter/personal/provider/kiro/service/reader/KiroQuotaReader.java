package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.provider.kiro.domain.KiroCreditEvent;
import com.llmeter.personal.quota.dto.CreditQuota;
import com.llmeter.personal.quota.dto.CreditQuota.CreditAllowance;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Reads the account/getUsage extension response from Kiro CLI 3.1; no session totals are involved. */
public final class KiroQuotaReader {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public Optional<QuotaSnapshot> read(final byte[] bytes, final Instant queriedAt) {
        try {
            final var root = mapper.readTree(bytes);
            if (!root.path("success").isBoolean() || !root.path("success").booleanValue()) throw failure();
            final var data = root.path("data");
            if (data.isMissingNode() || data.isNull()) return Optional.empty(); // Admin-managed accounts may omit billing.
            final var allowances = new ArrayList<CreditAllowance>();
            final var breakdowns = array(data, "usageBreakdowns");
            if (breakdowns.size() != 1 || !"CREDIT".equals(breakdowns.get(0).path("resourceType").asString())) throw failure();
            final var subscription = breakdowns.get(0);
            if (!subscription.path("hasLimit").isBoolean()) throw failure();
            if (subscription.path("hasLimit").booleanValue()) allowances.add(allowance("SUBSCRIPTION", "Plan credits", subscription, "limit", null, null));
            for (final var bonus : array(data, "bonusCredits")) {
                final var days = bonus.path("daysUntilExpiry");
                if (!days.isIntegralNumber() || !days.canConvertToInt() || days.intValue() < 0) throw failure();
                allowances.add(allowance("BONUS", text(bonus, "name"), bonus, "total", null, days.intValue()));
            }
            for (final var addon : array(data, "addOnCredits")) {
                final var expiry = addon.path("expiresAt");
                final LocalDate expiresOn = expiry.isNull() ? null : LocalDate.parse(text(addon, "expiresAt"), DateTimeFormatter.ofPattern("MMM dd, uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT));
                allowances.add(allowance("ADD_ON", "Add-on credits", addon, "total", expiresOn, null));
            }
            if (allowances.isEmpty()) return Optional.empty();
            final var reset = text(data, "billingCycleReset");
            final LocalDate renewal = reset.equals("Unknown") ? null : LocalDate.parse(reset);
            if (!data.path("overagesEnabled").isBoolean()) throw failure();
            final var credits = new CreditQuota(text(data, "planName"), renewal, data.path("overagesEnabled").booleanValue(), allowances);
            return Optional.of(new QuotaSnapshot(ProviderType.KIRO, null, null, null, queriedAt, queriedAt,
                    null, null, List.of(), "KIRO_CLI", 0, credits));
        } catch (final RuntimeException e) { throw failure(); }
    }
    private CreditAllowance allowance(final String kind, final String name, final JsonNode value, final String limitField,
                                      final LocalDate expiresOn, final Integer days) {
        final var used = amount(value, "used");
        final var limit = amount(value, limitField);
        return new CreditAllowance(kind, name, used.toPlainString(), limit.toPlainString(),
                limit.subtract(used).max(BigDecimal.ZERO).toPlainString(), expiresOn, days);
    }
    private BigDecimal amount(final JsonNode value, final String key) {
        final var node = value.path(key);
        if (!node.isNumber()) throw failure();
        final var amount = node.decimalValue();
        KiroCreditEvent.validate(amount);
        return amount;
    }
    private JsonNode array(final JsonNode value, final String key) {
        final var node = value.path(key);
        if (!node.isArray() || node.size() > 64) throw failure();
        return node;
    }
    private String text(final JsonNode value, final String key) {
        final var node = value.path(key);
        if (!node.isString() || node.stringValue().isBlank() || node.stringValue().length() > 160
                || node.stringValue().chars().anyMatch(Character::isISOControl)) throw failure();
        return node.stringValue();
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.INVALID_METRICS, "Kiro account quota could not be read safely");
    }
}
