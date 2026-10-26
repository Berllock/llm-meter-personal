package com.llmeter.personal.provider.domain;

public enum ProviderCollectionFailure {
    READ_FAILURE("The source could not be read"),
    NO_TOKEN_COUNT("No token count was found in the session"),
    INVALID_JSON("The session contains invalid JSON"),
    INVALID_METRICS("The session contains invalid token counts"),
    INVALID_IDENTITY("The session has no valid identity"),
    UNSUPPORTED_HISTORY("The usage history cannot be attributed safely"),
    CONFLICTING_DATA("The session data conflicts with another observation"),
    INVALID_TIMESTAMP("A token event has an invalid or missing timestamp"),
    INVALID_SEQUENCE("Token events have conflicting or regressing positions or timestamps"),
    COUNTER_RESET("A cumulative token counter decreased"),
    OVERFLOW("The token total exceeds the supported range"),
    UNKNOWN("Usage collection failed for an unclassified reason");

    private final String description;

    ProviderCollectionFailure(final String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
