package com.llmeter.personal.provider.codex.dto;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;

import java.util.List;

public record CodexCollectionIssue(List<Integer> fileNumbers, ProviderCollectionFailure category) {
    public CodexCollectionIssue {
        fileNumbers = List.copyOf(fileNumbers);
    }

    public String summary() {
        final var scope = fileNumbers.isEmpty() ? "Directory" : "Files " + fileNumbers;
        return scope + " [" + category + "]: " + category.description();
    }
}
