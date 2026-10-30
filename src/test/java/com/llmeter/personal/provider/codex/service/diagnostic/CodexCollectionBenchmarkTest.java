package com.llmeter.personal.provider.codex.service.diagnostic;

import com.llmeter.personal.provider.codex.service.CodexDailyCoverageCollector;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionFiles;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.usage.domain.UsagePeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class CodexCollectionBenchmarkTest {
    @TempDir Path directory;
    private final UsagePeriod period = new UsagePeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), ZoneId.of("UTC"));

    private Path fixture() throws Exception {
        final var fixture = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI());
        return Files.writeString(directory.resolve("PRIVATE_FILENAME.jsonl"),
                Files.readString(fixture).replace("\"parent\"", "\"PRIVATE_ID\""));
    }

    private record Execution(int code, String output, String error) {
        List<JsonNode> records() {
            final var mapper = new JsonMapper();
            return output.lines().map(mapper::readTree).toList();
        }
    }

    private Execution execute(final String... args) {
        final var output = new ByteArrayOutputStream();
        final var error = new ByteArrayOutputStream();
        final int code;
        try (final var stdout = new PrintStream(output, true, StandardCharsets.UTF_8);
             final var stderr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            code = CodexCollectionBenchmark.run(args, stdout, stderr);
        }
        return new Execution(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    // Validates the phase split retains established deduplication, pending coverage, and proven child attribution.
    @Test void shouldShareProductionAggregationWithTheDiagnostic() throws Exception {
        final var root = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/parent.jsonl")).toURI());
        final var child = Path.of(Objects.requireNonNull(getClass().getResource("/codex/daily/child.jsonl")).toURI());
        final var pending = Files.writeString(directory.resolve("pending.jsonl"),
                "{\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"pending\",\"history_mode\":\"paginated\"}}\n");
        final var files = List.of(root, child, root, pending);
        final var reader = new CodexSessionReader();
        final var collector = new CodexDailyCoverageCollector();
        assertThat(collector.aggregateObservations(files.stream()
                .map((final var file) -> reader.readDailyObservation(file, period.zone())).toList(), period))
                .isEqualTo(collector.collect(files, period));
    }

    // Validates technical file counts, metadata byte inventory, and nonnegative phase timing without brittle speed assertions.
    @Test void shouldMeasureAFullScanWithoutChangingItsSource() throws Exception {
        final var file = fixture();
        final var before = Files.readAllBytes(file);
        final var sample = new CollectionProfiler().measure(directory, period, 1);
        assertThat(sample.outcome()).isEqualTo("SUCCESS");
        assertThat(sample.filesDiscovered()).isEqualTo(1);
        assertThat(sample.filesRead()).isEqualTo(1);
        assertThat(sample.sourceBytesBefore()).isEqualTo(Files.size(file));
        assertThat(sample.sourceChanged()).isFalse();
        assertThat(sample.discoveryMs()).isGreaterThanOrEqualTo(0);
        assertThat(sample.readingMs()).isGreaterThanOrEqualTo(0);
        assertThat(sample.aggregationMs()).isGreaterThanOrEqualTo(0);
        assertThat(sample.measurementWallMs()).isGreaterThanOrEqualTo(sample.collectionWallMs());
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    // Validates a source change after a file was read invalidates the sample's stable-source classification.
    @Test void shouldFlagAnAppendDuringMeasurement() throws Exception {
        fixture();
        final var profiler = new CollectionProfiler((final var file) -> {
            try {
                Files.writeString(file, "\n", StandardOpenOption.APPEND);
            } catch (final java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        final var sample = profiler.measure(directory, period, 1);
        assertThat(sample.outcome()).isEqualTo("SUCCESS");
        assertThat(sample.sourceChanged()).isTrue();
    }

    // Validates JSONL output contains only technical measurements, never paths, identities, or token metric fields.
    @Test void shouldKeepLocalReportsPrivate() throws Exception {
        fixture();
        final var result = execute("--source", directory.toString(), "--warmup", "0", "--iterations", "2");
        assertThat(result.code()).isZero();
        assertThat(result.records()).hasSize(4);
        assertThat(result.output()).doesNotContain(directory.toString(), "PRIVATE_ID", "PRIVATE_FILENAME", "inputTokens", "outputTokens", "cachedTokens");
        assertThat(result.records().getLast().path("stableSuccessfulSamples").intValue()).isEqualTo(2);
        assertThat(result.error()).isEmpty();
    }

    // Validates malformed real-source data is reported as a safe category, without exception text or false successful medians.
    @Test void shouldReportFailureWithoutSourceContent() throws Exception {
        Files.writeString(directory.resolve("PRIVATE_FILENAME.jsonl"), "PRIVATE_CONTENT");
        final var result = execute("--source", directory.toString(), "--warmup", "0", "--iterations", "1");
        assertThat(result.code()).isZero();
        assertThat(result.records().get(1).path("failure").stringValue()).isEqualTo("INVALID_JSON");
        assertThat(result.records().getLast().path("medianCollectionWallMs").isNull()).isTrue();
        assertThat(result.output()).doesNotContain(directory.toString(), "PRIVATE_CONTENT", "PRIVATE_FILENAME", "Exception");
    }

    // Validates the real-source CLI can never select a mutation scenario or synthetic generation parameters.
    @ParameterizedTest @ValueSource(strings = {"append", "partial-write"})
    void shouldRejectMutationModesForLocalSources(final String scenario) throws Exception {
        final var file = fixture();
        final var before = Files.readAllBytes(file);
        final var result = execute("--source", directory.toString(), "--scenario", scenario);
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).doesNotContain(directory.toString(), "PRIVATE");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    // Validates generated appends grow the measured inventory and can move pending files into observed coverage.
    @Test void shouldGenerateRepresentativeAppendUpdates() throws Exception {
        try (final var workload = new SyntheticWorkload(4, 3, 1, 32)) {
            final var files = new CodexSessionFiles().find(workload.directory());
            final var collector = new CodexDailyCoverageCollector();
            assertThat(collector.collect(files, period).coverage().pendingSessions()).isEqualTo(1);
            final long before = new CollectionProfiler().measure(workload.directory(), period, 1).sourceBytesBefore();
            workload.advance("append");
            final var after = new CollectionProfiler().measure(workload.directory(), period, 2);
            assertThat(after.outcome()).isEqualTo("SUCCESS");
            assertThat(after.sourceBytesBefore()).isGreaterThan(before);
            assertThat(collector.collect(files, period).coverage().pendingSessions()).isZero();
        }
    }

    // Validates an interrupted synthetic JSONL line fails safely and completing it recovers on the next scan.
    @Test void shouldReproducePartialWriteAndRecovery() {
        final var result = execute("--scenario", "partial-write", "--files", "2", "--pending", "0",
                "--events", "2", "--payload-bytes", "8", "--warmup", "0", "--iterations", "2");
        assertThat(result.code()).isZero();
        final var records = result.records();
        assertThat(records.get(1).path("failure").stringValue()).isEqualTo(ProviderCollectionFailure.INVALID_JSON.name());
        assertThat(records.get(2).path("outcome").stringValue()).isEqualTo("SUCCESS");
        assertThat(records.getLast().path("failedSamples").intValue()).isEqualTo(1);
        assertThat(records.getLast().path("stableSuccessfulSamples").intValue()).isEqualTo(1);
    }

    // Validates generated workload ownership deletes only its temporary tree and respects its memory/disk guard.
    @Test void shouldCleanGeneratedDataAndRejectExcessiveWorkloads() throws Exception {
        final Path generated;
        try (final var workload = new SyntheticWorkload(2, 1, 0, 1)) {
            generated = workload.directory();
            assertThat(Files.isDirectory(generated)).isTrue();
        }
        assertThat(Files.exists(generated)).isFalse();
        final var result = execute("--files", "10000", "--events", "10000", "--pending", "0");
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.output()).isEmpty();
    }
    // Validates a small synthetic source does not inherit an impossible default number of pending files.
    @Test void shouldSupportASingleFileWorkloadWithDefaultPendingCount() {
        final var result = execute("--files", "1", "--events", "1", "--warmup", "0", "--iterations", "1");
        assertThat(result.code()).isZero();
        assertThat(result.records().getFirst().path("initialPendingFiles").intValue()).isZero();
        assertThat(result.records().getLast().path("stableSuccessfulSamples").intValue()).isEqualTo(1);
    }

}
