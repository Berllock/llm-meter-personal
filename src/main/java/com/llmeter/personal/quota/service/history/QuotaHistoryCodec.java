package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

/** Whitelists reduced quota data; predictions and local collection timestamps never create new samples. */
public final class QuotaHistoryCodec {
    private record WindowKey(String scope, String name) {}
    final JsonMapper mapper = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public QuotaSnapshot reduced(final QuotaSnapshot snapshot) {
        if (snapshot == null || snapshot.provider() == null || snapshot.observedAt() == null || snapshot.collectedAt() == null
                || snapshot.used() != null || snapshot.limit() != null || snapshot.resetAt() != null
                || snapshot.windows().size() > 512 || snapshot.excludedWindows() > 512) throw invalid();
        label(snapshot.planType()); label(snapshot.limitId());
        final boolean kiro = snapshot.provider() == ProviderType.KIRO && "KIRO_CLI".equals(snapshot.source());
        final boolean ide = snapshot.provider() == ProviderType.ANTIGRAVITY && "ANTIGRAVITY_IDE".equals(snapshot.source());
        if (!kiro && !ide && !(snapshot.provider() == ProviderType.CODEX && snapshot.source() == null)) throw invalid();
        if (kiro) {
            if (!snapshot.windows().isEmpty() || snapshot.creditQuota() == null) throw invalid();
            final var credits = snapshot.creditQuota();
            text(credits.planName(), 160);
            if (credits.allowances().isEmpty() || credits.allowances().size() > 129) throw invalid();
            int subscriptions = 0;
            for (final var allowance : credits.allowances()) {
                if (!Set.of("SUBSCRIPTION", "BONUS", "ADD_ON").contains(allowance.kind())) throw invalid();
                text(allowance.name(), 160);
                final var used = amount(allowance.used());
                final var limit = amount(allowance.limit());
                if (amount(allowance.remaining()).compareTo(limit.subtract(used).max(BigDecimal.ZERO)) != 0) throw invalid();
                if (allowance.kind().equals("SUBSCRIPTION") && ++subscriptions > 1) throw invalid();
                if (allowance.expiresOn() != null && !allowance.kind().equals("ADD_ON")) throw invalid();
                if (allowance.daysUntilExpiry() != null && (!allowance.kind().equals("BONUS") || allowance.daysUntilExpiry() < 0)) throw invalid();
            }
        } else {
            if (snapshot.creditQuota() != null || snapshot.windows().isEmpty()) throw invalid();
            final var identities = new HashSet<WindowKey>();
            for (final var window : snapshot.windows()) {
                if (window.observedAt() == null) throw invalid();
                label(window.limitId());
                if (ide ? window.name() == null || !window.name().matches("[a-z0-9][a-z0-9_-]{0,31}")
                        : !Set.of("primary", "secondary").contains(window.name()) || window.windowMinutes() == null) throw invalid();
                if (window.displayName() != null) text(window.displayName(), 200);
                if (!identities.add(new WindowKey(window.limitId(), window.name()))) throw invalid();
            }
        }
        final var windows = snapshot.windows().stream().map(window -> new QuotaWindow(window.name(), window.usedPercent(),
                window.windowMinutes(), window.resetAt(), window.limitId(), window.observedAt(), null, window.displayName()))
                .sorted(Comparator.comparing(QuotaWindow::limitId, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(QuotaWindow::name)).toList();
        return new QuotaSnapshot(snapshot.provider(), null, null, null, snapshot.collectedAt(), snapshot.observedAt(),
                snapshot.planType(), snapshot.limitId(), windows, snapshot.source(), snapshot.excludedWindows(), snapshot.creditQuota());
    }

    /** Original v1 checksum, retained to read legacy records without changing their IDs. */
    public String identity(final String context, final QuotaSnapshot snapshot) {
        final var normalized = new QuotaSnapshot(snapshot.provider(), null, null, null, snapshot.observedAt(), snapshot.observedAt(),
                snapshot.planType(), snapshot.limitId(), snapshot.windows(), snapshot.source(), snapshot.excludedWindows(), snapshot.creditQuota());
        return hash(context + "\n" + mapper.writeValueAsString(normalized));
    }

    /** Version 2 binds new record identity to the captured account context as well as the reduced snapshot. */
    public String identity(final String context, final QuotaSnapshot snapshot, final String accountFingerprint) {
        return hash("quota-history-v2\n" + identity(context, snapshot) + "\n" + (accountFingerprint == null ? "unverified" : accountFingerprint));
    }

    public static String hash(final String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (final NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private BigDecimal amount(final String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,18})?")) throw invalid();
        return new BigDecimal(value);
    }
    private void label(final String value) {
        if (value != null && !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw invalid();
    }
    private void text(final String value, final int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid reduced quota observation"); }
}
