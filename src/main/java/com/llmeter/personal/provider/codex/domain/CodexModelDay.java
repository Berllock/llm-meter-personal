package com.llmeter.personal.provider.codex.domain;

import java.time.LocalDate;

/** Null model means the recorded increment cannot be associated reliably. */
public record CodexModelDay(LocalDate date, String model) { }
