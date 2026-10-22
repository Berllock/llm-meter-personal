package com.llmeter.personal.provider.codex;

import com.llmeter.personal.provider.LlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.domain.dto.QuotaSnapshot;
import com.llmeter.personal.usage.domain.dto.UsageSnapshot;

import java.nio.file.Path;
import java.util.Optional;

public class CodexAdapter implements LlmProviderAdapter {

    private final Path sessionFile;

    public CodexAdapter(final Path sessionFile) {
        this.sessionFile = sessionFile;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public Optional<QuotaSnapshot> getQuota(){
        return Optional.empty();
    }

    @Override
    public UsageSnapshot getUsage() {
        throw new UnsupportedOperationException("Not implemented yet");
    }




}
