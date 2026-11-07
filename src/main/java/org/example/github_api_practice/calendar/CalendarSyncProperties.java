package org.example.github_api_practice.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("calendar.sync")
public record CalendarSyncProperties(@DefaultValue("5") int maxAttempts,
                                     @DefaultValue("30s") Duration initialBackoff,
                                     @DefaultValue("15m") Duration maxBackoff,
                                     @DefaultValue("60s") Duration leaseDuration,
                                     @DefaultValue("5m") Duration reconcileInterval,
                                     @DefaultValue("20") int batchSize) {
    public CalendarSyncProperties {
        if (maxAttempts < 1 || maxAttempts > 20 || initialBackoff.compareTo(Duration.ofSeconds(1)) < 0
                || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0
                || leaseDuration.compareTo(Duration.ofSeconds(60)) < 0
                || reconcileInterval.compareTo(Duration.ofMinutes(1)) < 0 || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Calendar 재시도 설정 범위가 올바르지 않습니다.");
        }
    }
}
