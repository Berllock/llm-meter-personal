package com.llmeter.personal.provider.codex.service.diagnostic;

import com.llmeter.personal.provider.codex.dto.CodexDailyObservation;
import com.llmeter.personal.provider.codex.service.CodexDailyCoverageCollector;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionFiles;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.usage.domain.UsagePeriod;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Local diagnostic only: output deliberately has no usage, identities, or source paths. */
final class CollectionProfiler {
    private final Consumer<Path> afterRead;

    CollectionProfiler() {
        this((final var ignored) -> { });
    }

    CollectionProfiler(final Consumer<Path> afterRead) {
        this.afterRead = afterRead;
    }

    record Sample(
            String type, int iteration, String outcome, ProviderCollectionFailure failure,
            Integer filesDiscovered, int filesRead, Long sourceBytesBefore, Boolean sourceChanged,
            double discoveryMs, double inventoryMs, double readingMs, double aggregationMs, double verificationMs,
            double collectionWallMs, double measurementWallMs, Double processCpuMs,
            long heapBeforeBytes, long heapAfterBytes, Long rssBeforeBytes, Long rssAfterBytes,
            Long gcCollections, Long gcTimeMs
    ) { }

    private record Fingerprint(long bytes, FileTime modified, Object key) { }

    Sample measure(final Path source, final UsagePeriod period, final int iteration) {
        final var resourcesBefore = Resources.capture();
        final long started = System.nanoTime();
        final var timings = new Timings();
        final var finder = new CodexSessionFiles();
        Integer filesDiscovered = null;
        Long sourceBytes = null;
        Map<Path, Fingerprint> before = null;
        final int[] filesRead = {0};
        ProviderCollectionFailure failure = null;
        Boolean changed = null;
        try {
            final var files = timings.run(0, () -> finder.find(source));
            filesDiscovered = files.size();
            before = timings.run(1, () -> snapshot(files));
            sourceBytes = before.values().stream().mapToLong(Fingerprint::bytes).reduce(0L, Math::addExact);
            final var observations = timings.run(2, () -> {
                final var reader = new CodexSessionReader();
                final var results = new ArrayList<CodexDailyObservation>();
                for (final var file : files) {
                    results.add(reader.readDailyObservation(file, period.zone()));
                    filesRead[0]++;
                    afterRead.accept(file);
                }
                return results;
            });
            // Consume the result without logging or retaining any token values in the diagnostic report.
            timings.run(3, () -> new CodexDailyCoverageCollector().aggregateObservations(observations, period));
        } catch (final ProviderCollectionException e) {
            failure = e.failure();
        } catch (final RuntimeException e) {
            failure = ProviderCollectionFailure.UNKNOWN;
        }
        if (before != null) {
            try {
                final var after = timings.run(4, () -> snapshot(finder.find(source)));
                changed = !before.equals(after);
            } catch (final RuntimeException e) {
                // The collection may have succeeded even if the subsequent metadata check is unavailable.
                changed = null;
            }
        }
        final long elapsed = System.nanoTime() - started;
        final var resourcesAfter = Resources.capture();
        final var cpu = difference(resourcesBefore.cpuNanos(), resourcesAfter.cpuNanos());
        return new Sample("sample", iteration, failure == null ? "SUCCESS" : "FAILURE", failure,
                filesDiscovered, filesRead[0], sourceBytes, changed,
                millis(timings.nanos[0]), millis(timings.nanos[1]), millis(timings.nanos[2]),
                millis(timings.nanos[3]), millis(timings.nanos[4]),
                millis(timings.nanos[0] + timings.nanos[2] + timings.nanos[3]), millis(elapsed),
                cpu == null ? null : millis(cpu), resourcesBefore.heapBytes(), resourcesAfter.heapBytes(),
                resourcesBefore.rssBytes(), resourcesAfter.rssBytes(),
                difference(resourcesBefore.gcCount(), resourcesAfter.gcCount()),
                difference(resourcesBefore.gcMillis(), resourcesAfter.gcMillis()));
    }

    private static Map<Path, Fingerprint> snapshot(final List<Path> files) {
        final Map<Path, Fingerprint> result = new LinkedHashMap<>();
        try {
            for (final var file : files) {
                final var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                result.put(file, new Fingerprint(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()));
            }
        } catch (final IOException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Source metadata unavailable");
        }
        return result;
    }

    private static final class Timings {
        private final long[] nanos = new long[5];
        <T> T run(final int stage, final Supplier<T> operation) {
            final long started = System.nanoTime();
            try {
                return operation.get();
            } finally {
                nanos[stage] += System.nanoTime() - started;
            }
        }
    }

    private record Resources(Long cpuNanos, long heapBytes, Long rssBytes, Long gcCount, Long gcMillis) {
        static Resources capture() {
            final var os = ManagementFactory.getOperatingSystemMXBean();
            final Long cpu = os instanceof com.sun.management.OperatingSystemMXBean bean
                    ? available(bean.getProcessCpuTime()) : null;
            long collections = 0;
            long time = 0;
            boolean available = false;
            for (final var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector.getCollectionCount() >= 0 && collector.getCollectionTime() >= 0) {
                    available = true;
                    collections += collector.getCollectionCount();
                    time += collector.getCollectionTime();
                }
            }
            return new Resources(cpu, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    rss(), available ? collections : null, available ? time : null);
        }
        private static Long rss() {
            try {
                for (final var line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("VmRSS:")) {
                        return Math.multiplyExact(Long.parseLong(line.trim().split("\\s+")[1]), 1024L);
                    }
                }
            } catch (final IOException | RuntimeException e) {
                // RSS is optional and currently supported only through Linux procfs.
            }
            return null;
        }
        private static Long available(final long value) {
            return value < 0 ? null : value;
        }
    }

    private static Long difference(final Long before, final Long after) {
        return before == null || after == null || after < before ? null : after - before;
    }
    private static double millis(final long nanos) {
        return nanos / 1_000_000.0;
    }
}
