package org.example.github_api_practice.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("calendar.http")
public record CalendarHttpProperties(@DefaultValue("3s") Duration connectTimeout,
                                     @DefaultValue("5s") Duration readTimeout) {
    public CalendarHttpProperties {
        if (connectTimeout.compareTo(Duration.ofMillis(50)) < 0 || readTimeout.compareTo(Duration.ofMillis(50)) < 0
                || connectTimeout.compareTo(Duration.ofMinutes(1)) > 0 || readTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Calendar 통신 제한 시간이 올바르지 않습니다.");
        }
    }
}
