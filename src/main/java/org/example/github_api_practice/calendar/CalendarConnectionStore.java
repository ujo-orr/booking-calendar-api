package org.example.github_api_practice.calendar;

import org.example.github_api_practice.calendar.dto.CalendarConnectionResponse;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CalendarConnectionStore {
    private final CalendarConnectionRepository repository;
    private final CalendarTokenCipher cipher;
    private final Clock clock;

    public CalendarConnectionStore(CalendarConnectionRepository repository, CalendarTokenCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    public void connect(Long memberId, OAuth2AuthorizedClient client) {
        var token = client.getAccessToken();
        if (!token.getScopes().contains(CalendarScopes.EVENTS_OWNED)) {
            throw new ApiException(ErrorCode.CALENDAR_PERMISSION_REQUIRED);
        }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.CALENDAR_CONNECTION_FAILED);
        }
        CalendarConnection connection = repository.findById(memberId).orElseGet(() -> new CalendarConnection(memberId));
        String refreshToken = client.getRefreshToken() == null ? null
                : cipher.encrypt(client.getRefreshToken().getTokenValue(), context(memberId, "refresh"));
        connection.update(cipher.encrypt(token.getTokenValue(), context(memberId, "access")), refreshToken,
                token.getExpiresAt(), String.join(" ", token.getScopes()));
        repository.saveAndFlush(connection);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Snapshot snapshot(Long memberId) {
        CalendarConnection connection = repository.findById(memberId)
                .orElseThrow(() -> new CalendarException(CalendarFailure.NOT_CONNECTED));
        return new Snapshot(memberId, connection.getEncryptedAccessToken(), connection.getEncryptedRefreshToken(),
                connection.getAccessTokenExpiresAt(), Set.copyOf(Arrays.asList(connection.getScopes().split(" "))),
                connection.isReauthRequired(), connection.getVersion());
    }

    public void refreshed(Snapshot original, String accessToken, String refreshToken, Instant expiresAt, String scopes) {
        CalendarConnection connection = repository.findById(original.memberId()).orElseThrow();
        // 늦게 끝난 갱신이 더 최근의 재연결 정보를 덮어쓰지 않도록 하는 버전 검사
        if (!connection.getVersion().equals(original.version())) {
            throw new CalendarException(CalendarFailure.PROVIDER_UNAVAILABLE);
        }
        connection.update(cipher.encrypt(accessToken, context(original.memberId(), "access")),
                refreshToken == null ? null : cipher.encrypt(refreshToken, context(original.memberId(), "refresh")),
                expiresAt, scopes);
        repository.flush();
    }

    public void requireReauth(Snapshot original) {
        repository.findById(original.memberId()).filter(connection -> connection.getVersion().equals(original.version()))
                .ifPresent(CalendarConnection::requireReauth);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public CalendarConnectionResponse status(Long memberId) {
        return repository.findById(memberId).map(connection -> {
            boolean refreshAvailable = connection.getEncryptedRefreshToken() != null;
            boolean needsReauth = connection.isReauthRequired()
                    || (!refreshAvailable && !connection.getAccessTokenExpiresAt().isAfter(clock.instant()));
            return new CalendarConnectionResponse(needsReauth ? "REAUTH_REQUIRED" : "CONNECTED", refreshAvailable,
                    connection.getAccessTokenExpiresAt());
        }).orElseGet(() -> new CalendarConnectionResponse("NOT_CONNECTED", false, null));
    }

    static String context(Long memberId, String kind) {
        return memberId + ":" + kind;
    }

    public record Snapshot(Long memberId, String encryptedAccessToken, String encryptedRefreshToken,
                           Instant expiresAt, Set<String> scopes, boolean reauthRequired, Long version) {
    }
}
