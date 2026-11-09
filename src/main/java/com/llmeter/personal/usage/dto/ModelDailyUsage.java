package com.llmeter.personal.usage.dto;

import com.llmeter.personal.provider.domain.ProviderType;
import java.time.LocalDate;

public record ModelDailyUsage(ProviderType provider, LocalDate date, String zone, String model,
                             Long inputTokens, Long outputTokens, Long cachedTokens) { }
