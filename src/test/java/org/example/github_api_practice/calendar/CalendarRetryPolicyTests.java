package org.example.github_api_practice.calendar;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarRetryPolicyTests {
    private final CalendarRetryPolicy policy = new CalendarRetryPolicy(new CalendarSyncProperties(20,
            Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), 20));

    @Test
    void exponentialBackoffAndJitterNeverExceedConfiguredCap() {
        assertThat(policy.delay(1, null)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(36));
        assertThat(policy.delay(2, null)).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(72));
        assertThat(policy.delay(20, null)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void providerRetryAfterCanRequireLongerWaitThanBackoffCap() {
        assertThat(policy.delay(20, Duration.ofHours(2))).isEqualTo(Duration.ofHours(2));
    }
}
