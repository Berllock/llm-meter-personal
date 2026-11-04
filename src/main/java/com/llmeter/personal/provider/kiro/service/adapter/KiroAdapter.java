package com.llmeter.personal.provider.kiro.service.adapter;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.provider.kiro.domain.KiroCreditEvent;
import com.llmeter.personal.provider.kiro.dto.KiroSessionUsage;
import com.llmeter.personal.provider.kiro.service.reader.KiroSessionReader;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.ProviderCoverage;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class KiroAdapter implements LlmProviderAdapter {
    private final Path directory;
    private final KiroSessionReader reader = new KiroSessionReader();
    public KiroAdapter(final Path directory) { this.directory = directory; }
    @Override
    public ProviderType provider() { return ProviderType.KIRO; }
    @Override
    public Optional<QuotaSnapshot> getQuota() { return Optional.empty(); }
    @Override
    public UsageSnapshot getUsage() {
        final var report = getDailyUsageReport(new UsagePeriod(LocalDate.MIN, LocalDate.MAX, ZoneId.of("UTC")));
        strict(report);
        BigDecimal total = BigDecimal.ZERO;
        for (final var row : report.usage()) { total = total.add(new BigDecimal(row.credits())); KiroCreditEvent.validate(total); }
        return new UsageSnapshot(provider(), null, null, null, Instant.now(), report.usage().isEmpty() ? null : total.toPlainString());
    }
    @Override
    public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        final var report = getDailyUsageReport(period);
        strict(report);
        return report.usage();
    }
    private void strict(final ProviderDailyUsage report) {
        if (report.coverage().pendingSessions() > 0) throw new ProviderCollectionException(
                ProviderCollectionFailure.NO_USAGE_RECORD, "Kiro sessions have no recorded credit summaries yet");
    }
    @Override
    public ProviderDailyUsage getDailyUsageReport(final UsagePeriod period) {
        final var days = new TreeMap<LocalDate, BigDecimal>();
        final Map<String, KiroCreditEvent> events = new HashMap<>();
        final Map<String, KiroSessionUsage> sessions = new HashMap<>();
        final Map<String, KiroCreditEvent> executions = new HashMap<>();
        int observed = 0;
        int pending = 0;
        // Native sessions may be grouped by workspace; inspect only the root and two directory levels.
        try (final var folders = Files.walk(directory.toRealPath(), 2)) {
            for (final var folder : folders.sorted().toList()) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.notExists(folder.resolve("session.json"))) {
                    if (Files.exists(folder.resolve("messages.jsonl"))) throw new ProviderCollectionException(
                            ProviderCollectionFailure.INVALID_IDENTITY, "Kiro session metadata is missing");
                    continue;
                }
                final var session = reader.read(folder);
                final var copy = sessions.putIfAbsent(session.sessionId(), session);
                if (copy != null) {
                    if (!copy.equals(session)) throw new ProviderCollectionException(
                            ProviderCollectionFailure.CONFLICTING_DATA, "Kiro session copies conflict");
                    continue;
                }
                if (session.events().isEmpty()) pending++; else observed++;
                for (final var event : session.events()) {
                    final var previous = events.putIfAbsent(event.id(), event);
                    final var execution = executions.putIfAbsent(event.executionId(), event);
                    if ((previous != null && !previous.equals(event)) || (execution != null && !execution.equals(event))) {
                        throw new ProviderCollectionException(ProviderCollectionFailure.CONFLICTING_DATA, "Kiro credit events conflict");
                    }
                    if (previous != null || execution != null) continue;
                    final var date = event.timestamp().atZone(period.zone()).toLocalDate();
                    final var total = days.getOrDefault(date, BigDecimal.ZERO).add(event.credits());
                    KiroCreditEvent.validate(total);
                    days.put(date, total);
                }
            }
        } catch (final IOException | UncheckedIOException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not discover Kiro sessions", e);
        }
        final var rows = days.entrySet().stream().filter((final var entry) -> period.contains(entry.getKey()))
                .map((final var entry) -> new DailyUsage(provider(), entry.getKey(), period.zone().getId(),
                        null, null, null, entry.getValue().toPlainString())).toList();
        return new ProviderDailyUsage(rows, new ProviderCoverage(provider(), observed, pending, false));
    }
}
