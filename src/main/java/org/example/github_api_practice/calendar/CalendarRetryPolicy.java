package org.example.github_api_practice.calendar;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class CalendarRetryPolicy {
    private final CalendarSyncProperties properties;

    public CalendarRetryPolicy(CalendarSyncProperties properties) {
        this.properties = properties;
    }

    public Duration delay(int attempt, Duration retryAfter) {
        long base = Math.min(properties.maxBackoff().toMillis(),
                properties.initialBackoff().toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 19)));
        long jitter = ThreadLocalRandom.current().nextLong(base / 5 + 1);
        Duration delay = Duration.ofMillis(Math.min(properties.maxBackoff().toMillis(), base + jitter));
        return retryAfter != null && retryAfter.compareTo(delay) > 0 ? retryAfter : delay;
    }
}
