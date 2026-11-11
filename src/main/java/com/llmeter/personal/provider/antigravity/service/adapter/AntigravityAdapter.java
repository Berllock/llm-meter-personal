package com.llmeter.personal.provider.antigravity.service.adapter;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.antigravity.domain.AntigravityTokenCounts;
import com.llmeter.personal.provider.antigravity.service.reader.AntigravitySessionReader;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.ProviderCoverage;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.TreeMap;

public final class AntigravityAdapter implements LlmProviderAdapter {
    private final Path brainDirectory;
    private final AntigravitySessionReader reader = new AntigravitySessionReader();

    private final Supplier<Optional<QuotaSnapshot>> quota;
    public AntigravityAdapter(final Path brainDirectory) { this(brainDirectory, Optional::empty); }
    public AntigravityAdapter(final Path brainDirectory, final Supplier<Optional<QuotaSnapshot>> quota) {
        this.brainDirectory = brainDirectory; this.quota = quota;
    }

    @Override
    public ProviderType provider() { return ProviderType.ANTIGRAVITY; }

    @Override
    public UsageSnapshot getUsage() {
        final var report = getDailyUsageReport(new UsagePeriod(LocalDate.MIN, LocalDate.MAX, ZoneId.of("UTC")));
        if (report.coverage().pendingSessions() > 0) throw new ProviderCollectionException(
                ProviderCollectionFailure.NO_TOKEN_COUNT, "Antigravity sessions have no recorded counters yet");
        AntigravityTokenCounts total = null;
        for (final var day : report.usage()) {
            final var counts = new AntigravityTokenCounts(day.inputTokens(), day.outputTokens(), day.cachedTokens());
            total = total == null ? counts : total.add(counts);
        }
        return new UsageSnapshot(provider(), total == null ? null : total.input(), total == null ? null : total.output(),
                total == null ? null : total.cached(), Instant.now());
    }

    @Override
    public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        final var report = getDailyUsageReport(period);
        if (report.coverage().pendingSessions() > 0) throw new ProviderCollectionException(
                ProviderCollectionFailure.NO_TOKEN_COUNT, "Antigravity sessions have no recorded counters yet");
        return report.usage();
    }

    @Override
    public ProviderDailyUsage getDailyUsageReport(final UsagePeriod period) {
        final var days = new TreeMap<LocalDate, AntigravityTokenCounts>();
        int observed = 0;
        int pending = 0;
        // Enumerate conversation directories, never transcript aliases, chunks, artifacts, or recursive copies.
        try (final var conversations = Files.list(brainDirectory)) {
            for (final var folder : conversations.sorted().toList()) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                final var logs = folder.resolve(".system_generated/logs");
                final var transcript = logs.resolve("transcript_full.jsonl");
                if (!Files.exists(transcript)) {
                    if (Files.exists(logs.resolve("transcript.jsonl"))) throw new ProviderCollectionException(
                            ProviderCollectionFailure.UNSUPPORTED_HISTORY, "A complete Antigravity transcript is unavailable");
                    // Artifact-only conversations do not establish readable source sessions.
                    continue;
                }
                final var session = reader.read(transcript, period.zone());
                if (session.observed()) observed++; else pending++;
                session.days().forEach((final var date, final var counts) -> days.merge(date, counts, AntigravityTokenCounts::add));
            }
        } catch (final IOException e) {
            throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not discover Antigravity transcripts", e);
        }
        final var rows = days.entrySet().stream()
                .filter((final var entry) -> !entry.getKey().isBefore(period.from()) && !entry.getKey().isAfter(period.to()))
                .map((final var entry) -> new DailyUsage(provider(), entry.getKey(), period.zone().getId(),
                        entry.getValue().input(), entry.getValue().output(), entry.getValue().cached())).toList();
        return new ProviderDailyUsage(rows, new ProviderCoverage(provider(), observed, pending));
    }

    @Override
    public Optional<QuotaSnapshot> getQuota() { return quota.get(); }
}
