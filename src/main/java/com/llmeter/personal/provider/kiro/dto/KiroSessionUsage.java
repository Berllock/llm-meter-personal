package com.llmeter.personal.provider.kiro.dto;

import com.llmeter.personal.provider.kiro.domain.KiroCreditEvent;
import java.util.List;

public record KiroSessionUsage(String sessionId, List<KiroCreditEvent> events) {
    public KiroSessionUsage { events = List.copyOf(events); }
}
