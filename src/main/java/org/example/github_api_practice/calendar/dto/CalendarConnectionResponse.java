package org.example.github_api_practice.calendar.dto;

import java.time.Instant;

public record CalendarConnectionResponse(String state, boolean refreshTokenAvailable, Instant accessTokenExpiresAt) {
}
