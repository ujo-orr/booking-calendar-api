package org.example.github_api_practice.calendar;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Component
public class CalendarHttpErrors {
    private static final Set<String> RATE_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded");
    private final Clock clock;

    public CalendarHttpErrors(Clock clock) {
        this.clock = clock;
    }

    public CalendarException classify(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        boolean rateLimited = status == 429;
        if (status == 403) {
            try {
                var body = exception.getResponseBodyAs(ErrorEnvelope.class);
                rateLimited = body != null && body.error() != null && body.error().errors() != null
                        && body.error().errors().stream().anyMatch(reason -> RATE_REASONS.contains(reason.reason()));
            } catch (RuntimeException ignored) {
                // 응답 원문을 남기지 않는 안전한 기본 오류 분류
            }
        }
        CalendarFailure failure = rateLimited ? CalendarFailure.RATE_LIMITED
                : status == 403 ? CalendarFailure.PERMISSION_DENIED
                : status >= 500 ? CalendarFailure.PROVIDER_UNAVAILABLE
                : status == 409 ? CalendarFailure.EVENT_CONFLICT
                : status == 408 ? CalendarFailure.TRANSPORT_ERROR : CalendarFailure.INVALID_RESPONSE;
        String header = exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After");
        return new CalendarException(failure, retryAfter(header));
    }

    private Duration retryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            Duration duration;
            if (header.matches("[0-9]+")) {
                duration = Duration.ofSeconds(Long.parseLong(header));
            } else {
                duration = Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            }
            if (duration.isNegative() || duration.isZero()) {
                return null;
            }
            return duration.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : duration;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    record ErrorEnvelope(ProviderError error) {
    }

    record ProviderError(List<Reason> errors) {
    }

    record Reason(String reason) {
    }
}
