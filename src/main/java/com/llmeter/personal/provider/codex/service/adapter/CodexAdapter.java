package com.llmeter.personal.provider.codex.service.adapter;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.codex.service.cache.CodexDailyObservationCache;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionFiles;
import com.llmeter.personal.provider.codex.service.reader.CodexSessionReader;
import com.llmeter.personal.provider.codex.service.CodexDailyUsageAggregator;
import com.llmeter.personal.provider.codex.service.CodexSessionAggregator;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.ProviderDailyUsage;
import com.llmeter.personal.provider.codex.service.CodexDailyCoverageCollector;
import com.llmeter.personal.usage.dto.UsageSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class CodexAdapter implements LlmProviderAdapter {

    private final Path sourcePath;
    private final boolean directoryMode;
    private final CodexSessionReader sessionReader = new CodexSessionReader();
    private final CodexSessionFiles sessionFiles = new CodexSessionFiles();
    private final CodexSessionAggregator sessionAggregator = new CodexSessionAggregator();
    private final CodexDailyObservationCache observationCache = new CodexDailyObservationCache();

    public CodexAdapter(final Path sessionFile) {
        this(sessionFile, false);
    }

    private CodexAdapter(final Path sourcePath, final boolean directoryMode) {
        this.sourcePath = sourcePath;
        this.directoryMode = directoryMode;
    }

    public static CodexAdapter forDirectory(final Path directory) {
        return new CodexAdapter(directory, true);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public Optional<QuotaSnapshot> getQuota() {
        return Optional.empty();
    }

    @Override
    public UsageSnapshot getUsage() {
        if (!directoryMode) {
            return sessionReader.read(sourcePath).usage();
        }

        final var sessions = sessionFiles.find(sourcePath).stream()
                .map(sessionReader::read)
                .toList();
        return sessionAggregator.aggregate(sessions);
    }

    @Override
    public ProviderDailyUsage getDailyUsageReport(final UsagePeriod period) {
        final List<Path> files;
        try {
            files = directoryMode ? sessionFiles.find(sourcePath) : List.of(sourcePath);
        } catch (final ProviderCollectionException e) {
            observationCache.clear();
            throw e;
        }
        return new CodexDailyCoverageCollector().aggregateObservations(
                observationCache.readAll(files, period.zone()), period);
    }

    @Override
    public List<DailyUsage> getDailyUsage(final UsagePeriod period) {
        final var files = directoryMode ? sessionFiles.find(sourcePath) : List.of(sourcePath);
        return new CodexDailyUsageAggregator().aggregate(files, period);
    }
}
