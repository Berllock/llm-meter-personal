package com.llmeter.personal.provider;

public class ProviderCollectionException extends RuntimeException {

    public ProviderCollectionException(final String message) {
        super(message);
    }

    public ProviderCollectionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
