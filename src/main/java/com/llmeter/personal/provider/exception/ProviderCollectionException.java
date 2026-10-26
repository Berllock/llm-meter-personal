package com.llmeter.personal.provider.exception;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;

import java.util.Objects;

public class ProviderCollectionException extends RuntimeException {

    private final ProviderCollectionFailure failure;

    public ProviderCollectionException(final String message) {
        this(ProviderCollectionFailure.UNKNOWN, message, null);
    }

    public ProviderCollectionException(final String message, final Throwable cause) {
        this(ProviderCollectionFailure.UNKNOWN, message, cause);
    }

    public ProviderCollectionException(final ProviderCollectionFailure failure, final String message) {
        this(failure, message, null);
    }

    public ProviderCollectionException(
            final ProviderCollectionFailure failure, final String message, final Throwable cause
    ) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure);
    }

    public ProviderCollectionFailure failure() {
        return failure;
    }
}
