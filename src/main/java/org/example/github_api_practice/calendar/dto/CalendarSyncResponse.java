package org.example.github_api_practice.calendar.dto;

import org.example.github_api_practice.calendar.CalendarFailure;
import org.example.github_api_practice.calendar.CalendarSyncStatus;

import java.time.Instant;

public record CalendarSyncResponse(Long reservationId, CalendarSyncStatus status, String googleEventId,
                                   CalendarFailure lastError, Instant updatedAt, int attemptCount, int maxAttempts,
                                   Instant nextAttemptAt, Instant leaseUntil, boolean retryExhausted) {
}
