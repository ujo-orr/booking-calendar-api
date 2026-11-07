package org.example.github_api_practice.calendar;

import java.time.Duration;

public class CalendarException extends RuntimeException {
    private final CalendarFailure failure;
    private final Duration retryAfter;

    public CalendarException(CalendarFailure failure) {
        this(failure, null);
    }

    public CalendarException(CalendarFailure failure, Duration retryAfter) {
        super(failure.name());
        this.failure = failure;
        this.retryAfter = retryAfter;
    }

    public CalendarFailure getFailure() {
        return failure;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
