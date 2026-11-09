package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CodexQuotaReaderTest {
    @TempDir Path directory;
    private final CodexQuotaReader reader = new CodexQuotaReader();
    private Path write(final String name, final String content) throws Exception { return Files.writeString(directory.resolve(name), content); }
    private String event(final String time, final String scope, final String plan, final double percent) { return """
            {"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","rate_limits":{"limit_id":"%s","plan_type":"%s","primary":{"used_percent":%s,"window_minutes":300,"resets_at":1793000000}}}}
            """.formatted(time, scope, plan, percent); }
    // Validates the latest event per scope wins independently of file order and percentages are never summed.
    @Test void shouldSelectLatestPerScope() throws Exception {
        final var old = write("new-name.jsonl", event("2026-10-25T10:00:00Z", "codex", "plus", 10));
        final var newer = write("old-name.jsonl", event("2026-10-25T11:00:00Z", "codex", "plus", 40)
                + event("2026-10-25T10:30:00Z", "model-x", "plus", 20));
        final var result = reader.read(List.of(newer, old)).orElseThrow();
        assertThat(result.windows()).extracting(window -> window.usedPercent()).containsExactly(40.0, 20.0);
        assertThat(result.windows()).extracting(window -> window.observedAt().toString()).containsExactly("2026-10-25T11:00:00Z", "2026-10-25T10:30:00Z");
        assertThat(result.observedAt().toString()).isEqualTo("2026-10-25T11:00:00Z");
        assertThat(result.used()).isNull();
        assertThat(result.limit()).isNull();
        assertThat(result.planType()).isEqualTo("plus");
    }
    // Validates stale quota stays a historical observation rather than becoming a zero-used new window.
    @Test void shouldPreserveExpiredObservation() throws Exception {
        final var file = write("source.jsonl", event("2026-10-25T10:00:00Z", "codex", "plus", 100));
        final var result = reader.read(List.of(file, file)).orElseThrow();
        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().getFirst().usedPercent()).isEqualTo(100);
        assertThat(result.windows().getFirst().resetAt()).isBefore(java.time.Instant.now());
    }
    // Validates absent or explicitly cleared observations do not invent a plan allowance.
    @Test void shouldReturnUnavailableWithoutWindows() throws Exception {
        assertThat(reader.read(List.of())).isEmpty();
        final var file = write("source.jsonl", event("2026-10-25T10:00:00Z", "codex", "plus", 10)
                + "{\"timestamp\":\"2026-10-25T11:00:00Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"rate_limits\":{\"limit_id\":\"codex\",\"plan_type\":\"plus\"}}}\n");
        assertThat(reader.read(List.of(file))).isEmpty();
    }
    // Validates a plan change does not mix an older plan's distinct model quota into the current result.
    @Test void shouldNotMixPlans() throws Exception {
        final var file = write("source.jsonl", event("2026-10-25T10:00:00Z", "model-x", "plus", 70)
                + event("2026-10-25T11:00:00Z", "codex", "pro", 20));
        final var result = reader.read(List.of(file)).orElseThrow();
        assertThat(result.planType()).isEqualTo("pro");
        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().getFirst().limitId()).isEqualTo("codex");
    }
    // Validates unsupported numeric values and malformed timestamps/JSON fail safely.
    @ParameterizedTest @ValueSource(strings = {"negative", "overflow", "minutes", "reset", "timestamp", "json"})
    void shouldRejectInvalidEvidence(final String scenario) throws Exception {
        final var original = event("2026-10-25T10:00:00Z", "codex", "plus", 40);
        final var value = switch (scenario) {
            case "negative" -> original.replace("40.0", "-1");
            case "overflow" -> original.replace("40.0", "101");
            case "minutes" -> original.replace("300", "0");
            case "reset" -> original.replace("1793000000", "-1");
            case "timestamp" -> original.replace("2026-10-25T10:00:00Z", "invalid");
            default -> "{";
        };
        final var file = write("source.jsonl", value);
        assertThatThrownBy(() -> reader.read(List.of(file))).isInstanceOf(ProviderCollectionException.class);
    }
    // Validates contradictory equal-time evidence cannot be resolved by arbitrary file order.
    @Test void shouldRejectConflictingCopies() throws Exception {
        final var first = write("first.jsonl", event("2026-10-25T10:00:00Z", "codex", "plus", 10));
        final var second = write("second.jsonl", event("2026-10-25T10:00:00Z", "codex", "plus", 20));
        assertThatThrownBy(() -> reader.read(List.of(first, second))).isInstanceOf(ProviderCollectionException.class);
    }
    // Validates known distinct accounts are not merged into a fictional single account quota.
    @Test void shouldRejectMultipleAccounts() throws Exception {
        final var header = "{\"type\":\"session_meta\",\"payload\":{\"creator_account_id\":\"synthetic-a\"}}\n";
        final var first = write("first.jsonl", header + event("2026-10-25T10:00:00Z", "codex", "plus", 10));
        final var second = write("second.jsonl", header.replace("synthetic-a", "synthetic-b"));
        assertThatThrownBy(() -> reader.read(List.of(first, second))).isInstanceOf(ProviderCollectionException.class);
    }
}
