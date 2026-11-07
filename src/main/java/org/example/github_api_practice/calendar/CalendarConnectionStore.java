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
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CalendarConnectionStore {
    private final CalendarConnectionRepository repository;
    private final CalendarTokenCipher cipher;
    private final Clock clock;
    private final CalendarSyncProperties properties;

    public CalendarConnectionStore(CalendarConnectionRepository repository, CalendarTokenCipher cipher, Clock clock,
                                    CalendarSyncProperties properties) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
        this.properties = properties;
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
        return snapshot(connection);
    }

    private Snapshot snapshot(CalendarConnection connection) {
        return new Snapshot(connection.getMemberId(), connection.getEncryptedAccessToken(), connection.getEncryptedRefreshToken(),
                connection.getAccessTokenExpiresAt(), Set.copyOf(Arrays.asList(connection.getScopes().split(" "))),
                connection.isReauthRequired(), connection.getVersion());
    }

    public RefreshWork claimRefresh(Long memberId) {
        CalendarConnection connection = repository.findLockedById(memberId)
                .orElseThrow(() -> new CalendarException(CalendarFailure.NOT_CONNECTED));
        if (connection.isReauthRequired()) {
            throw new CalendarException(CalendarFailure.REAUTH_REQUIRED);
        }
        if (connection.getAccessTokenExpiresAt().isAfter(clock.instant().plusSeconds(30))) {
            return new RefreshWork(snapshot(connection), null);
        }
        if (connection.refreshInProgress(clock.instant())) {
            throw new CalendarException(CalendarFailure.LOCAL_CONFLICT);
        }
        String token = UUID.randomUUID().toString();
        connection.claimRefresh(token, clock.instant().plus(properties.leaseDuration()));
        repository.flush();
        return new RefreshWork(snapshot(connection), token);
    }

    public void refreshed(Snapshot original, String leaseToken, String accessToken, String refreshToken,
                          Instant expiresAt, String scopes) {
        CalendarConnection connection = repository.findLockedById(original.memberId()).orElseThrow();
        // 늦게 끝난 갱신이 더 최근의 재연결 정보를 덮어쓰지 않도록 하는 버전 검사
        if (!connection.ownsRefresh(leaseToken) || !connection.getVersion().equals(original.version())) {
            throw new CalendarException(CalendarFailure.LOCAL_CONFLICT);
        }
        connection.update(cipher.encrypt(accessToken, context(original.memberId(), "access")),
                refreshToken == null ? null : cipher.encrypt(refreshToken, context(original.memberId(), "refresh")),
                expiresAt, scopes);
        repository.flush();
    }

    public void requireReauth(Snapshot original) {
        repository.findLockedById(original.memberId()).filter(connection -> connection.getVersion().equals(original.version())
                        && !connection.refreshInProgress(clock.instant()))
                .ifPresent(CalendarConnection::requireReauth);
    }

    public void finishRefresh(Long memberId, String leaseToken, boolean requireReauth) {
        repository.findLockedById(memberId).filter(connection -> connection.ownsRefresh(leaseToken)).ifPresent(connection -> {
            if (requireReauth) {
                connection.requireReauth();
            }
            connection.releaseRefresh();
        });
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

    public record RefreshWork(Snapshot snapshot, String leaseToken) {
    }
}
