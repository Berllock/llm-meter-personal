package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.quota.dto.QuotaForecast;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.dto.QuotaForecast.Status.*;

class CodexQuotaForecastTest {
    @TempDir Path directory;
    private final Instant now = Instant.parse("2026-11-09T12:04:00Z");
    private final CodexQuotaReader reader = new CodexQuotaReader(Clock.fixed(now, ZoneOffset.UTC));
    private final String header = "{\"type\":\"session_meta\",\"payload\":{\"creator_account_id\":\"PRIVATE_SYNTHETIC_ACCOUNT\"}}\n";
    private String event(final int seconds, final double percent, final String plan, final long reset) {
        return """
                {"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","rate_limits":{"limit_id":"codex","plan_type":"%s","primary":{"used_percent":%s,"window_minutes":300,"resets_at":%d}}}}
                """.formatted(now.minusSeconds(240).plusSeconds(seconds), plan, percent, reset);
    }
    private long reset() { return now.plusSeconds(14400).getEpochSecond(); }
    private Path write(final String name, final String content) throws Exception { return Files.writeString(directory.resolve(name), content); }
    private QuotaForecast forecast(final List<Path> files) { return reader.read(files).orElseThrow().windows().getFirst().forecast(); }
    // Validates unordered source files and copied records produce the same three genuine timestamp observations.
    @Test void shouldRebuildHistoryAndDeduplicateAcrossPolls() throws Exception {
        final var first = write("first.jsonl", header + event(240, 40, "plus", reset()));
        final var second = write("second.jsonl", header + event(0, 20, "plus", reset()) + event(120, 30, "plus", reset()));
        final var result = forecast(List.of(first, second, first));
        assertThat(result.status()).isEqualTo(ESTIMATED);
        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(forecast(List.of(second, first))).isEqualTo(result);
        assertThat(forecast(List.of(first, second))).isEqualTo(result);
        assertThat(new JsonMapper().writeValueAsString(reader.read(List.of(first, second)).orElseThrow())).doesNotContain("PRIVATE_SYNTHETIC_ACCOUNT");
    }
    // Validates plan/reset changes and missing identity prevent bridging incompatible history.
    @Test void shouldStopAtChangedContext() throws Exception {
        for (final String scenario : List.of("plan", "reset", "identity")) {
            final var content = (scenario.equals("identity") ? "" : header)
                    + event(0, 20, scenario.equals("plan") ? "pro" : "plus", scenario.equals("reset") ? reset() - 60 : reset())
                    + event(120, 30, "plus", reset()) + event(240, 40, "plus", reset());
            assertThat(forecast(List.of(write("source.jsonl", content))).status()).isEqualTo(scenario.equals("identity") ? UNVERIFIED_CONTEXT : INSUFFICIENT_DATA);
        }
    }
    // Validates an explicitly missing window invalidates the old baseline even if the same reset returns later.
    @Test void shouldNotBridgeClearedWindows() throws Exception {
        final var cleared = "{\"timestamp\":\"2026-11-09T12:01:00Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"rate_limits\":{\"limit_id\":\"codex\",\"plan_type\":\"plus\"}}}\n";
        final var source = write("source.jsonl", header + event(0, 20, "plus", reset()) + cleared
                + event(120, 30, "plus", reset()) + event(240, 40, "plus", reset()));
        assertThat(forecast(List.of(source)).status()).isEqualTo(INSUFFICIENT_DATA);
    }
    // Validates contradictions at older retained timestamps fail regardless of file traversal order.
    @Test void shouldRejectConflictingHistoricalCopies() throws Exception {
        final var first = write("first.jsonl", header + event(0, 20, "plus", reset()) + event(240, 40, "plus", reset()));
        final var second = write("second.jsonl", header + event(0, 21, "plus", reset()));
        assertThatThrownBy(() -> forecast(List.of(first, second))).isInstanceOf(ProviderCollectionException.class);
    }
    // Validates dense logs retain a bounded sample count rather than retaining unbounded source history.
    @Test void shouldBoundRetainedHistory() throws Exception {
        final var content = new StringBuilder(header);
        for (int index = 0; index < 300; index++) content.append(event(index - 59, index / 10.0, "plus", reset()));
        final var result = forecast(List.of(write("source.jsonl", content.toString())));
        assertThat(result.sampleCount()).isEqualTo(256);
        assertThat(result.status()).isEqualTo(ESTIMATED);
    }
}
