package com.llmeter.personal.provider.codex.service.diagnostic;

import com.llmeter.personal.usage.domain.UsagePeriod;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Manual, isolated-JVM diagnostic. Not a Spring component, HTTP endpoint, or JMH benchmark. */
public final class CodexCollectionBenchmark {
    private CodexCollectionBenchmark() { }

    public static void main(final String[] args) {
        final int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(final String[] args, final PrintStream output, final PrintStream error) {
        try {
            final var options = Options.parse(args);
            if (options.source() == null) {
                try (final var workload = new SyntheticWorkload(options.files(), options.events(),
                        options.pending(), options.payloadBytes())) {
                    execute(options, workload.directory(), workload, output);
                }
            } else {
                execute(options, options.source(), null, output);
            }
            return 0;
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Do not print exceptions, arguments, paths, source content, or token values.
            error.println("Benchmark could not be completed. Check arguments, source accessibility, and build artifacts.");
            return 1;
        }
    }

    private static void execute(final Options options, final Path source, final SyntheticWorkload workload,
                                final PrintStream output) throws Exception {
        final var mapper = new JsonMapper();
        output.println(mapper.writeValueAsString(new Header("environment", "collection-profile-v1",
                workload == null ? "LOCAL" : "SYNTHETIC", options.scenario(),
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors(),
                options.warmup(), options.iterations(), options.intervalMs(),
                options.period().from().toString(), options.period().to().toString(), options.period().zone().getId(),
                workload == null ? null : options.files(), workload == null ? null : options.events(),
                workload == null ? null : options.pending(), workload == null ? null : options.payloadBytes())));
        final var profiler = new CollectionProfiler();
        for (int i = 0; i < options.warmup(); i++) {
            profiler.measure(source, options.period(), -1);
        }
        final var samples = new ArrayList<CollectionProfiler.Sample>();
        for (int i = 0; i < options.iterations(); i++) {
            if (i > 0 && options.intervalMs() > 0) {
                Thread.sleep(options.intervalMs());
            }
            if (workload != null) {
                workload.advance(options.scenario());
            }
            final var sample = profiler.measure(source, options.period(), i + 1);
            samples.add(sample);
            output.println(mapper.writeValueAsString(sample));
        }
        output.println(mapper.writeValueAsString(summarize(samples)));
    }

    record Header(String type, String schema, String sourceKind, String scenario, String javaVersion,
                  String os, String architecture, long heapMaxBytes, int availableProcessors,
                  int warmup, int iterations, int intervalMs, String from, String to, String zone,
                  Integer syntheticFiles, Integer initialEventsPerObservedFile,
                  Integer initialPendingFiles, Integer syntheticPayloadBytes) { }

    record Summary(String type, int samples, long successfulSamples, long failedSamples,
                   long changedSamples, int stableSuccessfulSamples, Double medianCollectionWallMs,
                   Double medianDiscoveryMs, Double medianReadingMs, Double medianAggregationMs,
                   Double medianProcessCpuMs, Double maxCollectionWallMs) { }

    private static Summary summarize(final List<CollectionProfiler.Sample> samples) {
        final var stable = samples.stream().filter((final var sample) -> "SUCCESS".equals(sample.outcome())
                && Boolean.FALSE.equals(sample.sourceChanged())).toList();
        final var cpu = stable.stream().filter((final var sample) -> sample.processCpuMs() != null).toList();
        return new Summary("summary", samples.size(),
                samples.stream().filter((final var sample) -> "SUCCESS".equals(sample.outcome())).count(),
                samples.stream().filter((final var sample) -> "FAILURE".equals(sample.outcome())).count(),
                samples.stream().filter((final var sample) -> Boolean.TRUE.equals(sample.sourceChanged())).count(),
                stable.size(), median(stable, CollectionProfiler.Sample::collectionWallMs),
                median(stable, CollectionProfiler.Sample::discoveryMs), median(stable, CollectionProfiler.Sample::readingMs),
                median(stable, CollectionProfiler.Sample::aggregationMs), median(cpu, (final var sample) -> sample.processCpuMs()),
                stable.isEmpty() ? null : stable.stream().mapToDouble(CollectionProfiler.Sample::collectionWallMs).max().orElseThrow());
    }

    private static Double median(final List<CollectionProfiler.Sample> samples,
                                 final ToDoubleFunction<CollectionProfiler.Sample> metric) {
        if (samples.isEmpty()) {
            return null;
        }
        final var values = samples.stream().mapToDouble(metric).sorted().toArray();
        final int middle = values.length / 2;
        return values.length % 2 == 0 ? (values[middle - 1] + values[middle]) / 2 : values[middle];
    }

    private record Options(Path source, String scenario, int files, int events, int pending, int payloadBytes,
                           int warmup, int iterations, int intervalMs, UsagePeriod period) {
        static Options parse(final String[] args) {
            final Set<String> allowed = Set.of("source", "scenario", "files", "events", "pending", "payload-bytes",
                    "warmup", "iterations", "interval-ms", "from", "to", "zone");
            final var values = new HashMap<String, String>();
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length || !allowed.contains(args[i].substring(2))
                        || values.putIfAbsent(args[i].substring(2), args[i + 1]) != null) {
                    throw new IllegalArgumentException("Invalid benchmark arguments");
                }
            }
            final var source = values.containsKey("source") ? Path.of(values.get("source")) : null;
            final var scenario = values.getOrDefault("scenario", "static");
            if (!Set.of("static", "append", "partial-write").contains(scenario)
                    || (source != null && (!"static".equals(scenario)
                    || Set.of("files", "events", "pending", "payload-bytes").stream().anyMatch(values::containsKey)))) {
                throw new IllegalArgumentException("Local sources are read-only");
            }
            final int files = number(values, "files", 100, 1, 10_000);
            return new Options(source, scenario, files,
                    number(values, "events", 100, 1, 10_000), number(values, "pending", Math.min(5, files - 1), 0, 10_000),
                    number(values, "payload-bytes", 1024, 0, 4096), number(values, "warmup", 2, 0, 100),
                    number(values, "iterations", 5, 1, 1000), number(values, "interval-ms", 0, 0, 60_000),
                    new UsagePeriod(LocalDate.parse(values.getOrDefault("from", "2026-10-01")),
                            LocalDate.parse(values.getOrDefault("to", "2026-10-31")),
                            ZoneId.of(values.getOrDefault("zone", "UTC"))));
        }
        private static int number(final HashMap<String, String> values, final String name,
                                  final int fallback, final int minimum, final int maximum) {
            final int value = Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("Benchmark argument outside supported limits");
            }
            return value;
        }
    }
}
