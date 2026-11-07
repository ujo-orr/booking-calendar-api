package org.example.github_api_practice.calendar;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

@Service
public class GoogleCalendarClient {
    static final String MARKER = "bookingCalendarKey";
    private final RestClient client;
    private final CalendarTokenProvider tokens;
    private final CalendarHttpErrors errors;

    public GoogleCalendarClient(@Qualifier("calendarRestClient") RestClient client, CalendarTokenProvider tokens,
                                CalendarHttpErrors errors) {
        this.client = client;
        this.tokens = tokens;
        this.errors = errors;
    }

    public String ensureCreated(CalendarSyncStore.Work work) {
        String accessToken = tokens.accessToken(work.memberId());
        Lookup existing = lookup(work, accessToken);
        if (existing.event() != null) {
            verifyOwner(work, existing.event());
            verifyTimes(work, existing.event());
            return existing.event().id();
        }
        if (existing.gone() || work.knownEvent()) {
            throw new CalendarException(CalendarFailure.EVENT_MISSING);
        }
        ZoneId zone = ZoneId.of("Asia/Seoul");
        var body = new EventRequest(work.eventId(), "예약 #" + work.reservationId(),
                new EventTime(work.startsAt().atZone(zone).toOffsetDateTime(), zone.getId()),
                new EventTime(work.endsAt().atZone(zone).toOffsetDateTime(), zone.getId()),
                new ExtendedProperties(Map.of(MARKER, work.eventKey())));
        try {
            EventResponse response = client.post().uri("/calendars/primary/events")
                    .headers(headers -> headers.setBearerAuth(accessToken)).body(body)
                    .retrieve().body(EventResponse.class);
            if (response == null || !work.eventId().equals(response.id())) {
                throw new CalendarException(CalendarFailure.OUTCOME_UNKNOWN);
            }
            return response.id();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                Lookup conflict = lookup(work, accessToken);
                if (conflict.event() != null) {
                    verifyOwner(work, conflict.event());
                    verifyTimes(work, conflict.event());
                    return conflict.event().id();
                }
                throw new CalendarException(conflict.gone() ? CalendarFailure.EVENT_MISSING : CalendarFailure.EVENT_CONFLICT);
            }
            throw classify(work.memberId(), accessToken, exception);
        } catch (RestClientException exception) {
            // 생성 결과를 모를 때도 다음 시도에서 같은 ID를 조회하는 복구
            throw new CalendarException(CalendarFailure.OUTCOME_UNKNOWN);
        }
    }

    public void ensureDeleted(CalendarSyncStore.Work work) {
        String accessToken = tokens.accessToken(work.memberId());
        Lookup existing = lookup(work, accessToken);
        if (existing.event() == null) {
            return;
        }
        verifyOwner(work, existing.event());
        try {
            client.delete().uri("/calendars/primary/events/{eventId}", work.eventId())
                    .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status != 404 && status != 410) {
                throw classify(work.memberId(), accessToken, exception);
            }
        } catch (RestClientException exception) {
            throw new CalendarException(CalendarFailure.TRANSPORT_ERROR);
        }
    }

    private Lookup lookup(CalendarSyncStore.Work work, String accessToken) {
        try {
            EventResponse response = client.get().uri("/calendars/primary/events/{eventId}", work.eventId())
                    .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(EventResponse.class);
            if (response == null || !work.eventId().equals(response.id())) {
                throw new CalendarException(CalendarFailure.INVALID_RESPONSE);
            }
            return "cancelled".equals(response.status()) ? new Lookup(null, true) : new Lookup(response, false);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404 || status == 410) {
                return new Lookup(null, status == 410);
            }
            throw classify(work.memberId(), accessToken, exception);
        } catch (RestClientException exception) {
            throw new CalendarException(CalendarFailure.TRANSPORT_ERROR);
        }
    }

    private void verifyOwner(CalendarSyncStore.Work work, EventResponse response) {
        if (work.eventKey() != null && (response.extendedProperties() == null
                || response.extendedProperties().privateProperties() == null
                || !work.eventKey().equals(response.extendedProperties().privateProperties().get(MARKER)))) {
            throw new CalendarException(CalendarFailure.EVENT_IDENTITY_MISMATCH);
        }
    }

    private void verifyTimes(CalendarSyncStore.Work work, EventResponse response) {
        if (response.start() == null || response.end() == null || response.start().dateTime() == null
                || response.end().dateTime() == null || !response.start().dateTime().toInstant().equals(work.startsAt())
                || !response.end().dateTime().toInstant().equals(work.endsAt())) {
            throw new CalendarException(CalendarFailure.EVENT_MISMATCH);
        }
    }

    private CalendarException classify(Long memberId, String accessToken, RestClientResponseException exception) {
        if (exception.getStatusCode().value() == 401) {
            tokens.rejected(memberId, accessToken);
            return new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        return errors.classify(exception);
    }

    record EventRequest(String id, String summary, EventTime start, EventTime end, ExtendedProperties extendedProperties) {
    }

    record EventTime(OffsetDateTime dateTime, String timeZone) {
    }

    record ExtendedProperties(@JsonProperty("private") Map<String, String> privateProperties) {
    }

    record EventResponse(String id, String status, EventTime start, EventTime end, ExtendedProperties extendedProperties) {
    }

    private record Lookup(EventResponse event, boolean gone) {
    }
}
