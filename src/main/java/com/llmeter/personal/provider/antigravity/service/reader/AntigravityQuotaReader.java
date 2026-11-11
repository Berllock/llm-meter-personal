package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

/** Reads only quota buckets; account/profile fields and absolute amounts never become token counts. */
public final class AntigravityQuotaReader {
    private final JsonMapper mapper = new JsonMapper();
    public Optional<QuotaSnapshot> read(final byte[] bytes, final Instant queriedAt) {
        try { return readTree(mapper.readTree(bytes), queriedAt); }
        catch (final ProviderCollectionException e) { throw e; }
        catch (final RuntimeException e) { throw failure(); }
    }
    private Optional<QuotaSnapshot> readTree(final JsonNode root, final Instant queriedAt) {
        if (root == null || !root.isObject() || !root.path("response").isObject()) throw failure();
        final var groups = root.path("response").path("groups");
        if (groups.isMissingNode()) return Optional.empty(); // Empty proto repeated fields can be omitted.
        if (!groups.isArray() || groups.size() > 256) throw failure();
        final var windows = new LinkedHashMap<String, QuotaWindow>();
        int excluded = 0;
        int visited = 0;
        for (final var group : groups) {
            if (!group.isObject()) throw failure();
            final var buckets = group.path("buckets");
            if (buckets.isMissingNode()) continue;
            if (!buckets.isArray()) throw failure();
            for (final var bucket : buckets) {
                if (++visited > 512 || !bucket.isObject()) throw failure();
                final var disabled = bucket.path("disabled");
                if (!disabled.isMissingNode() && !disabled.isBoolean()) throw failure();
                if (disabled.asBoolean(false)) continue;
                final var id = bucket.path("bucketId");
                if (!id.isString() || !id.stringValue().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw failure();
                final var remaining = bucket.path("remainingFraction");
                if (remaining.isMissingNode() || remaining.isNull()) { excluded++; continue; }
                if (!remaining.isNumber() || !Double.isFinite(remaining.doubleValue()) || remaining.doubleValue() < 0 || remaining.doubleValue() > 1) throw failure();
                if (bucket.hasNonNull("remainingAmount")) throw failure();
                final var reset = bucket.path("resetTime");
                if (reset.isMissingNode() || reset.isNull()) { excluded++; continue; }
                if (!reset.isString()) throw failure();
                final var resetAt = Instant.parse(reset.stringValue());
                final var window = bucket.path("window");
                if (!window.isMissingNode() && !window.isString()) throw failure();
                final String label = window.isString() ? window.stringValue().trim().toLowerCase(Locale.ROOT) : "unreported";
                if (!label.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw failure();
                final var display = bucket.path("displayName");
                final String bucketName = display.isString() && !display.stringValue().isBlank() ? display.stringValue() : id.stringValue();
                final var groupDisplay = group.path("displayName");
                final String groupName = groupDisplay.isString() ? groupDisplay.stringValue().trim() : "";
                final String name = !groupName.isEmpty() && !groupName.equals(bucketName) ? groupName + " · " + bucketName : bucketName;
                if (name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) throw failure();
                final var value = new QuotaWindow(label, (1 - remaining.doubleValue()) * 100, minutes(label), resetAt,
                        id.stringValue(), queriedAt, null, name);
                final String identity = id.stringValue() + ":" + label;
                final var previous = windows.putIfAbsent(identity, value);
                if (previous != null && (Double.compare(previous.usedPercent(), value.usedPercent()) != 0 || !previous.resetAt().equals(value.resetAt()))) throw failure();
            }
        }
        if (windows.isEmpty()) return Optional.empty();
        return Optional.of(new QuotaSnapshot(ProviderType.ANTIGRAVITY, null, null, null, queriedAt, queriedAt,
                null, null, List.copyOf(windows.values()), "ANTIGRAVITY_IDE", excluded));
    }
    private Long minutes(final String window) {
        if (window.equals("daily")) return 1440L;
        if (window.equals("weekly")) return 10080L;
        if (!window.matches("[1-9][0-9]{0,6}[mhd]")) return null;
        final long count = Long.parseLong(window.substring(0, window.length() - 1));
        return count * switch (window.charAt(window.length() - 1)) { case 'h' -> 60; case 'd' -> 1440; default -> 1; };
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.INVALID_METRICS, "Antigravity IDE quota response is unsupported or inconsistent");
    }
}
