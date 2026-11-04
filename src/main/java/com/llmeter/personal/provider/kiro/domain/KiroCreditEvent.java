package com.llmeter.personal.provider.kiro.domain;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Recorded credits are a separate unit and never a conversion into tokens. */
public record KiroCreditEvent(String id, String executionId, Instant timestamp, BigDecimal credits) {
    public KiroCreditEvent {
        Objects.requireNonNull(credits);
        validate(credits);
    }
    public static void validate(final BigDecimal value) {
        if (value.signum() < 0) throw new ProviderCollectionException(
                ProviderCollectionFailure.INVALID_METRICS, "Kiro credit usage must be nonnegative");
        if (value.scale() > 18 || value.precision() - (long) value.scale() > 18) throw new ProviderCollectionException(
                ProviderCollectionFailure.OVERFLOW, "Kiro credit usage exceeds the supported decimal range");
    }
}
