package com.llmeter.personal.provider.kiro.service;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KiroAccountQuotaServiceTest {
    private static final byte[] QUOTA = """
            {"success":true,"data":{"planName":"Synthetic Free","billingCycleReset":"2026-12-01","overagesEnabled":false,
            "usageBreakdowns":[{"resourceType":"CREDIT","hasLimit":true,"used":10,"limit":50}],"bonusCredits":[],"addOnCredits":[]}}
            """.getBytes(StandardCharsets.UTF_8);
    // Validates polling reuses one account query per minute without changing the query timestamp.
    @Test
    void shouldThrottleQueriesAndRetryAfterOneMinute() {
        final var calls = new AtomicInteger();
        final var clock = mock(Clock.class);
        final var start = Instant.parse("2026-11-11T12:00:00Z");
        when(clock.instant()).thenReturn(start);
        final var service = new KiroAccountQuotaService(() -> {
            calls.incrementAndGet(); return QUOTA;
        }, clock);
        assertThat(service.getQuota().orElseThrow().observedAt()).isEqualTo(start);
        service.getQuota();
        assertThat(calls).hasValue(1);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        service.getQuota();
        assertThat(calls).hasValue(2);
    }
    // Validates failure details are sanitized and rapid retries never repeatedly invoke the CLI.
    @Test
    void shouldThrottleFailures() {
        final var calls = new AtomicInteger();
        final var service = new KiroAccountQuotaService(() -> {
            calls.incrementAndGet(); throw new IllegalStateException("PRIVATE_ACCOUNT_DATA");
        }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 2; i++) assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class)
                .hasMessage("Kiro account quota unavailable");
        assertThat(calls).hasValue(1);
    }
    // Validates a failed refresh cannot reuse an earlier successful account balance.
    @Test
    void shouldDiscardOldSuccessOnFailure() {
        final var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        final var calls = new AtomicInteger();
        final var service = new KiroAccountQuotaService(() -> calls.incrementAndGet() == 1
                ? QUOTA : new byte[0], clock);
        service.getQuota();
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(61));
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class);
        assertThatThrownBy(service::getQuota).isInstanceOf(ProviderCollectionException.class);
        assertThat(calls).hasValue(2);
    }
}
