package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KiroQuotaReaderTest {
    private final KiroQuotaReader reader = new KiroQuotaReader();
    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");
    private static final String RESPONSE = """
            {"success":true,"data":{"planName":"Synthetic Free","billingCycleReset":"2026-12-01","overagesEnabled":false,
            "usageBreakdowns":[{"resourceType":"CREDIT","hasLimit":true,"used":12.123456789012345678,"limit":50}],
            "bonusCredits":[{"name":"Synthetic bonus","used":1.25,"total":5,"daysUntilExpiry":3}],
            "addOnCredits":[{"used":0.5,"total":2,"expiresAt":"Dec 20, 2026"}]}}
            """;
    // Validates exact account decimals, separate pools and date-only renewal without inventing token windows.
    @Test
    void shouldReadSeparateCreditAllowances() {
        final var snapshot = reader.read(bytes(RESPONSE), now).orElseThrow();
        assertThat(snapshot.source()).isEqualTo("KIRO_CLI");
        assertThat(snapshot.windows()).isEmpty();
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.resetAt()).isNull();
        assertThat(snapshot.observedAt()).isEqualTo(now);
        final var credits = snapshot.creditQuota();
        assertThat(credits.renewalDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(credits.allowances()).hasSize(3);
        assertThat(credits.allowances().getFirst().remaining()).isEqualTo("37.876543210987654322");
        assertThat(credits.allowances().get(1).daysUntilExpiry()).isEqualTo(3);
        assertThat(credits.allowances().get(2).expiresOn()).isEqualTo(LocalDate.of(2026, 12, 20));
    }
    // Validates over-limit observations do not produce negative remaining credits or hide reported usage.
    @Test
    void shouldPreserveOverLimitUsageAndUnknownRenewal() {
        final var value = RESPONSE.replace("12.123456789012345678", "51.5").replace("2026-12-01", "Unknown");
        final var credits = reader.read(bytes(value), now).orElseThrow().creditQuota();
        assertThat(credits.renewalDate()).isNull();
        assertThat(credits.allowances().getFirst().used()).isEqualTo("51.5");
        assertThat(credits.allowances().getFirst().remaining()).isEqualTo("0");
    }
    // Validates admin-managed or unbounded accounts remain unavailable rather than receiving a guessed allowance.
    @Test
    void shouldKeepMissingAccountAllowanceUnknown() {
        assertThat(reader.read(bytes("{\"success\":true}"), now)).isEmpty();
        assertThat(reader.read(bytes("""
                {"success":true,"data":{"usageBreakdowns":[{"resourceType":"CREDIT","hasLimit":false}],"bonusCredits":[],"addOnCredits":[]}}
                """), now)).isEmpty();
    }
    // Validates malformed, unsupported or incomplete evidence fails with a safe message.
    @Test
    void shouldRejectInvalidEvidence() {
        for (final var invalid : new String[]{RESPONSE.replace("true,\"data\"", "false,\"data\""),
                RESPONSE.replace("CREDIT", "AGENTIC_REQUEST"), RESPONSE.replace("12.123456789012345678", "-1"),
                RESPONSE.replace("12.123456789012345678", "0.1234567890123456789"),
                RESPONSE.replace("2026-12-01", "2026-02-30"), RESPONSE.replace("\"limit\":50", "\"limit\":null"),
                RESPONSE.replace("\"daysUntilExpiry\":3", "\"daysUntilExpiry\":-1"),
                RESPONSE.replace("\"planName\":\"Synthetic Free\"", "\"planName\":\"bad\\nlabel\"")}) {
            assertThatThrownBy(() -> reader.read(bytes(invalid), now)).isInstanceOf(ProviderCollectionException.class)
                    .hasMessage("Kiro account quota could not be read safely");
        }
    }
    private byte[] bytes(final String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
