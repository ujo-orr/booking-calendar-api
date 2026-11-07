package org.example.github_api_practice.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({CalendarHttpProperties.class, CalendarSyncProperties.class})
public class CalendarHttpConfig {
    private final CalendarHttpProperties properties;

    public CalendarHttpConfig(CalendarHttpProperties properties, CalendarSyncProperties sync) {
        this.properties = properties;
        if (sync.leaseDuration().compareTo(properties.connectTimeout().plus(properties.readTimeout()).multipliedBy(4)) <= 0) {
            throw new IllegalArgumentException("Calendar 작업 임대 시간은 최대 통신 예산보다 길어야 합니다.");
        }
    }
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
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
