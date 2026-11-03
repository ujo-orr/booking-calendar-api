package org.example.github_api_practice.calendar;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.Arrays;

@Service
public class CalendarTokenProvider {
    private final CalendarConnectionStore store;
    private final CalendarTokenCipher cipher;
    private final ClientRegistrationRepository registrations;
    private final RestClient client;
    private final Clock clock;

    public CalendarTokenProvider(CalendarConnectionStore store, CalendarTokenCipher cipher,
                                 ClientRegistrationRepository registrations,
                                 @Qualifier("googleTokenRestClient") RestClient client, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.registrations = registrations;
        this.client = client;
        this.clock = clock;
    }

    public String accessToken(Long memberId) {
        var snapshot = store.snapshot(memberId);
        if (snapshot.reauthRequired() || !snapshot.scopes().contains(CalendarScopes.EVENTS_OWNED)) {
            throw new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        if (snapshot.expiresAt().isAfter(clock.instant().plusSeconds(30))) {
            return cipher.decrypt(snapshot.encryptedAccessToken(), CalendarConnectionStore.context(memberId, "access"));
        }
        if (snapshot.encryptedRefreshToken() == null) {
            store.requireReauth(snapshot);
            throw new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        var registration = registrations.findByRegistrationId(CalendarScopes.REGISTRATION_ID);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", cipher.decrypt(snapshot.encryptedRefreshToken(),
                CalendarConnectionStore.context(memberId, "refresh")));
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());
        TokenResponse response;
        try {
            response = client.post().uri(registration.getProviderDetails().getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException exception) {
            String error = null;
            try {
                var body = exception.getResponseBodyAs(TokenError.class);
                error = body == null ? null : body.error();
            } catch (RuntimeException ignored) {
                // 원문을 공개하지 않고 분류할 수 없는 오류로 처리
            }
            if ("invalid_grant".equals(error)) {
                store.requireReauth(snapshot);
                throw new CalendarException(CalendarFailure.REAUTH_REQUIRED);
            }
            if ("invalid_client".equals(error) || exception.getStatusCode().value() == 401) {
                throw new CalendarException(CalendarFailure.CONFIGURATION_ERROR);
            }
            throw new CalendarException(CalendarFailure.PROVIDER_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new CalendarException(CalendarFailure.PROVIDER_UNAVAILABLE);
        }
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() == null || response.expiresIn() < 1 || response.expiresIn() > 604800
                || !"Bearer".equalsIgnoreCase(response.tokenType())) {
            throw new CalendarException(CalendarFailure.INVALID_RESPONSE);
        }
        String scopes = response.scope() == null ? String.join(" ", snapshot.scopes()) : response.scope();
        if (!Arrays.asList(scopes.split(" ")).contains(CalendarScopes.EVENTS_OWNED)) {
            store.requireReauth(snapshot);
            throw new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        store.refreshed(snapshot, response.accessToken(), response.refreshToken(),
                clock.instant().plusSeconds(response.expiresIn()), scopes);
        return response.accessToken();
    }

    public void rejected(Long memberId, String rejectedToken) {
        var current = store.snapshot(memberId);
        String currentToken = cipher.decrypt(current.encryptedAccessToken(), CalendarConnectionStore.context(memberId, "access"));
        if (currentToken.equals(rejectedToken)) {
            store.requireReauth(current);
        }
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("refresh_token") String refreshToken,
                         @JsonProperty("expires_in") Long expiresIn,
                         @JsonProperty("token_type") String tokenType, String scope) {
    }

    record TokenError(String error) {
    }
}
