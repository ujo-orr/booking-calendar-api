package org.example.github_api_practice.calendar;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
public class GoogleCalendarClient {
    private final RestClient client;
    private final CalendarTokenProvider tokens;

    public GoogleCalendarClient(@Qualifier("calendarRestClient") RestClient client, CalendarTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public String create(Long memberId, Long reservationId, Instant startsAt, Instant endsAt) {
        String accessToken = tokens.accessToken(memberId);
        ZoneId zone = ZoneId.of("Asia/Seoul");
        var body = new EventRequest("예약 #" + reservationId,
                new EventTime(startsAt.atZone(zone).toOffsetDateTime(), zone.getId()),
                new EventTime(endsAt.atZone(zone).toOffsetDateTime(), zone.getId()));
        try {
            EventResponse response = client.post().uri("/calendars/primary/events")
                    .headers(headers -> headers.setBearerAuth(accessToken)).body(body)
                    .retrieve().body(EventResponse.class);
            if (response == null || response.id() == null || response.id().isBlank() || response.id().length() > 1024) {
                throw new CalendarException(CalendarFailure.OUTCOME_UNKNOWN);
            }
            return response.id();
        } catch (RestClientResponseException exception) {
            throw classify(memberId, accessToken, exception);
        } catch (RestClientException exception) {
            // Google에서 생성했는지 확인할 수 없는 통신 실패 상태
            throw new CalendarException(CalendarFailure.OUTCOME_UNKNOWN);
        }
    }

    public void delete(Long memberId, String eventId) {
        String accessToken = tokens.accessToken(memberId);
        try {
            client.delete().uri("/calendars/primary/events/{eventId}", eventId)
                    .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status != 404 && status != 410) {
                throw classify(memberId, accessToken, exception);
            }
        } catch (RestClientException exception) {
            throw new CalendarException(CalendarFailure.OUTCOME_UNKNOWN);
        }
    }

    private CalendarException classify(Long memberId, String accessToken, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 401) {
            tokens.rejected(memberId, accessToken);
            return new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        if (status == 403) {
            return new CalendarException(CalendarFailure.PERMISSION_DENIED);
        }
        return new CalendarException(status == 429 || status >= 500
                ? CalendarFailure.PROVIDER_UNAVAILABLE : CalendarFailure.INVALID_RESPONSE);
    }

    record EventRequest(String summary, EventTime start, EventTime end) {
    }

    record EventTime(OffsetDateTime dateTime, String timeZone) {
    }

    record EventResponse(String id) {
    }
}
