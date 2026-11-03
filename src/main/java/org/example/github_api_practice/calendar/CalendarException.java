package org.example.github_api_practice.calendar;

public class CalendarException extends RuntimeException {
    private final CalendarFailure failure;

    public CalendarException(CalendarFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public CalendarFailure getFailure() {
        return failure;
    }
}
