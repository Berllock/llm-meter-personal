package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AntigravityQuotaReaderTest {
    private final AntigravityQuotaReader reader = new AntigravityQuotaReader();
    private final Instant now = Instant.parse("2026-11-10T12:00:00Z");
    private String bucket(final String window, final String extra) {
        return "{\"bucketId\":\"model-standard\",\"displayName\":\"Synthetic model\",\"window\":\"" + window
                + "\",\"remainingFraction\":0.6,\"resetTime\":\"2026-11-10T15:00:00Z\"" + extra + "}";
    }
    private byte[] response(final String buckets) { return ("{\"email\":\"PRIVATE_IDENTITY\",\"response\":{\"groups\":[{\"buckets\":["+buckets+"]}]}}").getBytes(StandardCharsets.UTF_8); }
    // Validates fraction normalization, known/unknown durations and minimal DTOs without account identity or invented plan/capacity.
    @Test void shouldReadSupportedBuckets() throws Exception {
        final var result = reader.read(response(bucket("weekly", "")+","+bucket("custom", "")), now).orElseThrow();
        assertThat(result.windows()).hasSize(2);
        assertThat(result.windows().getFirst().usedPercent()).isCloseTo(40, within(0.000001));
        assertThat(result.windows().getFirst().windowMinutes()).isEqualTo(10080L);
        assertThat(result.windows().getLast().windowMinutes()).isNull();
        assertThat(result.windows().getFirst().displayName()).isEqualTo("Synthetic model");
        assertThat(result.windows()).allMatch(window -> window.forecast() == null);
        assertThat(result.observedAt()).isEqualTo(now);
        assertThat(result.source()).isEqualTo("ANTIGRAVITY_IDE");
        assertThat(result.planType()).isNull();
        assertThat(result.limit()).isNull();
        assertThat(new JsonMapper().writeValueAsString(result)).doesNotContain("PRIVATE_IDENTITY");
    }
    // Validates disabled entries are omitted and unsupported amounts are counted rather than converted into tokens or percentages.
    @Test void shouldExposePartialSupportedCoverage() {
        final var amount = "{\"bucketId\":\"amount-only\",\"remainingAmount\":100}";
        final var result = reader.read(response(bucket("5h", "") + "," + bucket("daily", ",\"disabled\":true") + "," + amount), now).orElseThrow();
        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().getFirst().windowMinutes()).isEqualTo(300L);
        assertThat(result.excludedWindows()).isEqualTo(1);
        assertThat(reader.read(response(amount), now)).isEmpty();
        assertThat(reader.read("{\"response\":{}}".getBytes(StandardCharsets.UTF_8), now)).isEmpty();
    }
    // Validates duplicate groups do not duplicate quota windows and conflicting copies fail explicitly.
    @Test void shouldReconcileRepeatedBuckets() {
        assertThat(reader.read(response(bucket("weekly", "")+","+bucket("weekly", "")), now).orElseThrow().windows()).hasSize(1);
        assertThatThrownBy(() -> reader.read(response(bucket("weekly", "")+","+bucket("weekly", "").replace("0.6", "0.4")), now)).isInstanceOf(ProviderCollectionException.class);
    }
    // Validates model/group labels remain visible without counting shared bucket identities more than once.
    @Test void shouldPreserveGroupLabelsAndDeduplicateSharedBuckets() {
        final var first = response(bucket("weekly", ""));
        final var grouped = new String(first, StandardCharsets.UTF_8).replace("\"buckets\":", "\"displayName\":\"Synthetic group\",\"buckets\":");
        final var result = reader.read(grouped.getBytes(StandardCharsets.UTF_8), now).orElseThrow();
        assertThat(result.windows().getFirst().displayName()).isEqualTo("Synthetic group · Synthetic model");
        final var twice = "{\"response\":{\"groups\":[{\"displayName\":\"Group one\",\"buckets\":[" + bucket("weekly", "")
                + "]},{\"displayName\":\"Group two\",\"buckets\":[" + bucket("weekly", "") + "]}]}}";
        assertThat(reader.read(twice.getBytes(StandardCharsets.UTF_8), now).orElseThrow().windows()).hasSize(1);
    }
    // Validates expired reset values remain historical and a zero remaining fraction is preserved as reported exhaustion.
    @Test void shouldPreserveExpiredAndZeroValues() {
        final var source = bucket("weekly", "").replace("0.6", "0").replace("2026-11-10T15:00:00Z", "2026-11-09T15:00:00Z");
        final var window = reader.read(response(source), now).orElseThrow().windows().getFirst();
        assertThat(window.usedPercent()).isEqualTo(100);
        assertThat(window.resetAt()).isBefore(now);
    }
    // Validates malformed numeric, identity, reset and oneof shapes cannot produce actionable quota.
    @ParameterizedTest @ValueSource(strings={"negative","overflow","string","timestamp","identity","disabled","oneof","json"})
    void shouldRejectUnsafeBuckets(final String scenario) {
        final var original = bucket("weekly", "");
        final var broken = switch (scenario) {
            case "negative" -> original.replace("0.6", "-0.1");
            case "overflow" -> original.replace("0.6", "1.1");
            case "string" -> original.replace("0.6", "\"0.6\"");
            case "timestamp" -> original.replace("2026-11-10T15:00:00Z", "invalid");
            case "identity" -> original.replace("model-standard", "unsafe bucket");
            case "disabled" -> bucket("weekly", ",\"disabled\":\"false\"");
            case "oneof" -> bucket("weekly", ",\"remainingAmount\":10");
            default -> "{";
        };
        assertThatThrownBy(() -> reader.read(response(broken), now)).isInstanceOf(ProviderCollectionException.class);
    }
}
