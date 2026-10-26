package com.llmeter.personal.usage.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public record UsagePeriod(LocalDate from, LocalDate to, ZoneId zone) {

    public UsagePeriod {
        Objects.requireNonNull(from, "Start date is required");
        Objects.requireNonNull(to, "End date is required");
        Objects.requireNonNull(zone, "Time zone is required");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must not be after end date");
        }
    }

    public boolean contains(final LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
