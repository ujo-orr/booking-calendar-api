package org.example.github_api_practice.calendar;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "calendar_connections")
public class CalendarConnection {
    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "access_token_expires_at", nullable = false)
    private Instant accessTokenExpiresAt;

    @Column(nullable = false, length = 2000)
    private String scopes;

    @Column(name = "reauth_required", nullable = false)
    private boolean reauthRequired;

    @Version
    private Long version;

    @Column(name = "refresh_lease_token", length = 36)
    private String refreshLeaseToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "refresh_lease_until")
    private Instant refreshLeaseUntil;

    public CalendarConnection(Long memberId) {
        this.memberId = memberId;
    }

    public void update(String accessToken, String refreshToken, Instant expiresAt, String scopes) {
        encryptedAccessToken = accessToken;
        if (refreshToken != null) {
            encryptedRefreshToken = refreshToken;
        }
        accessTokenExpiresAt = expiresAt;
        this.scopes = scopes;
        reauthRequired = false;
        releaseRefresh();
    }

    public void requireReauth() {
        reauthRequired = true;
    }

    public void claimRefresh(String token, Instant until) {
        refreshLeaseToken = token;
        refreshLeaseUntil = until;
    }

    public void releaseRefresh() {
        refreshLeaseToken = null;
        refreshLeaseUntil = null;
    }

    public boolean ownsRefresh(String token) {
        return token != null && token.equals(refreshLeaseToken);
    }

    public boolean refreshInProgress(Instant now) {
        return refreshLeaseUntil != null && refreshLeaseUntil.isAfter(now);
    }
}
