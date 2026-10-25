package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

import java.nio.file.Path;
import java.util.Optional;

public class CodexAdapter implements LlmProviderAdapter {

    private final Path sourcePath;
    private final boolean directoryMode;
    private final CodexSessionReader sessionReader = new CodexSessionReader();
    private final CodexSessionFiles sessionFiles = new CodexSessionFiles();
    private final CodexSessionAggregator sessionAggregator = new CodexSessionAggregator();

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
}
