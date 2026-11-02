package org.example.github_api_practice.reservation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ReservationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
