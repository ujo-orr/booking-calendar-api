package org.example.github_api_practice.calendar;

public enum CalendarFailure {
    NOT_CONNECTED,
    REAUTH_REQUIRED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    OUTCOME_UNKNOWN,
    CONFIGURATION_ERROR,
    INTERNAL_ERROR,
    RATE_LIMITED,
    TRANSPORT_ERROR,
    EVENT_CONFLICT,
    EVENT_IDENTITY_MISMATCH,
    EVENT_MISMATCH,
    EVENT_MISSING,
    LEGACY_OUTCOME_UNKNOWN,
    LOCAL_CONFLICT;

    public boolean retryable() {
        return switch (this) {
            case PROVIDER_UNAVAILABLE, OUTCOME_UNKNOWN, RATE_LIMITED, TRANSPORT_ERROR, EVENT_CONFLICT, LOCAL_CONFLICT -> true;
            default -> false;
        };
    }
}
