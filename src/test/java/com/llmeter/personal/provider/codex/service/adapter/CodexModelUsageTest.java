package com.llmeter.personal.provider.codex.service.adapter;

import com.llmeter.personal.provider.codex.service.cache.CodexDailyObservationCache;
import com.llmeter.personal.provider.codex.service.CodexDailyCoverageCollector;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CodexModelUsageTest {
    @TempDir Path directory;
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-26"), ZoneId.of("UTC"));
    private String header() { return """
            {"ordinal":0,"type":"session_meta","payload":{"id":"synthetic","history_mode":"paginated"}}
            """; }
    private String context(final int ordinal, final String model) { return """
            {"ordinal":%d,"type":"turn_context","payload":{"model":"%s"}}
            """.formatted(ordinal, model); }
    private String event(final int ordinal, final int total, final int last) { return """
            {"ordinal":%d,"timestamp":"2026-10-25T12:00:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":%d,"output_tokens":0,"cached_input_tokens":0},"last_token_usage":{"input_tokens":%d,"output_tokens":0,"cached_input_tokens":0}}}}
            """.formatted(ordinal, total, last); }
    private Path write(final String value) throws Exception { return Files.writeString(directory.resolve("synthetic.jsonl"), value); }

    // Validates exact model-switch increments partition the unchanged daily totals and matching copies count once.
    @Test void shouldPartitionByRecordedModel() throws Exception {
        final var file = write(header() + context(1, "model-a") + event(2, 100, 100) + context(3, "model-b") + event(4, 130, 30));
        Files.copy(file, directory.resolve("copy.jsonl"));
        final var report = CodexAdapter.forDirectory(directory).getDailyUsageReport(period);
        assertThat(report.usage().getFirst().inputTokens()).isEqualTo(130L);
        assertThat(report.modelUsage()).extracting(row -> row.model()).containsExactly("model-a", "model-b");
        assertThat(report.modelUsage()).extracting(row -> row.inputTokens()).containsExactly(100L, 30L);
    }
    // Validates a cumulative gap after switching cannot be charged entirely to the latest model.
    @Test void shouldKeepAmbiguousSwitchUnknown() throws Exception {
        write(header() + context(1, "model-a") + event(2, 100, 100) + context(3, "model-b") + event(4, 160, 20));
        final var report = CodexAdapter.forDirectory(directory).getDailyUsageReport(period);
        assertThat(report.modelUsage()).extracting(row -> row.model()).containsExactly(null, "model-a");
        assertThat(report.modelUsage()).extracting(row -> row.inputTokens()).containsExactly(60L, 100L);
        assertThat(report.usage().getFirst().inputTokens()).isEqualTo(160L);
    }
    // Validates absent or out-of-order optional context preserves numeric usage as unidentified model usage.
    @Test void shouldRetainUsageWithoutTrustedContext() throws Exception {
        write(header() + event(2, 100, 100) + context(1, "model-a") + event(4, 130, 30));
        final var report = CodexAdapter.forDirectory(directory).getDailyUsageReport(period);
        assertThat(report.modelUsage()).hasSize(1);
        assertThat(report.modelUsage().getFirst().model()).isNull();
        assertThat(report.modelUsage().getFirst().inputTokens()).isEqualTo(130L);
    }
    // Validates inherited parent context cannot label child-owned increments after the ownership boundary.
    @Test void shouldNotInheritParentModel() throws Exception {
        write(header().replace("\"history_mode\":\"paginated\"", "\"history_mode\":\"paginated\",\"parent_thread_id\":\"parent\",\"subagent_history_start_ordinal\":10")
                + context(1, "model-parent") + event(2, 100, 100) + event(11, 130, 30));
        final var report = CodexAdapter.forDirectory(directory).getDailyUsageReport(period);
        assertThat(report.modelUsage()).hasSize(1);
        assertThat(report.modelUsage().getFirst().model()).isNull();
        assertThat(report.modelUsage().getFirst().inputTokens()).isEqualTo(30L);
    }
    // Validates identical totals with conflicting model histories fail instead of selecting an arbitrary copy.
    @Test void shouldRejectConflictingCopies() throws Exception {
        final var content = header() + context(1, "model-a") + event(2, 100, 100);
        write(content);
        Files.writeString(directory.resolve("copy.jsonl"), content.replace("model-a", "model-b"));
        assertThatThrownBy(() -> CodexAdapter.forDirectory(directory).getDailyUsageReport(period)).isInstanceOf(ProviderCollectionException.class);
    }
    // Validates model changes invalidate cached attribution and model cardinality obeys the existing cache budget.
    @Test void shouldValidateModelContentAndBoundBuckets() throws Exception {
        final var file = write(header() + context(1, "model-a") + event(2, 100, 100));
        final var cache = new CodexDailyObservationCache(10, 1);
        final var collector = new CodexDailyCoverageCollector();
        collector.aggregateObservations(cache.readAll(List.of(file), period.zone()), period);
        final var time = Files.getLastModifiedTime(file);
        Files.writeString(file, Files.readString(file).replace("model-a", "model-b"));
        Files.setLastModifiedTime(file, time);
        assertThat(collector.aggregateObservations(cache.readAll(List.of(file), period.zone()), period).modelUsage().getFirst().model()).isEqualTo("model-b");
        assertThat(cache.statistics().parseAttempts()).isEqualTo(2);
        Files.writeString(file, Files.readString(file) + context(3, "model-c") + event(4, 130, 30));
        assertThat(collector.aggregateObservations(cache.readAll(List.of(file), period.zone()), period).modelUsage()).hasSize(2);
        assertThat(cache.statistics().entries()).isZero();
    }
}
