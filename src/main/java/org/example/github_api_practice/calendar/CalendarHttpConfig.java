package org.example.github_api_practice.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class CalendarHttpConfig {
    @Bean
    public RestClient calendarRestClient(RestClient.Builder builder,
                                        @Value("${calendar.api-base-url:https://www.googleapis.com/calendar/v3}") String url) {
        return builder.clone().requestFactory(requestFactory()).baseUrl(url).build();
    }

    @Bean
    public RestClient googleTokenRestClient(RestClient.Builder builder) {
        return builder.clone().requestFactory(requestFactory()).build();
    }

    private JdkClientHttpRequestFactory requestFactory() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
