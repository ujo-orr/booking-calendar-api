package org.example.github_api_practice.calendar;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_calendar_sync")
public class ReservationCalendarSync {
    @Id
    @Column(name = "reservation_id")
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CalendarSyncStatus status;

    @Column(name = "google_event_id", length = 1024)
    private String googleEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error", length = 40)
    private CalendarFailure lastError;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "create_dispatched", nullable = false)
    private boolean createDispatched;

    public ReservationCalendarSync(Long reservationId, CalendarSyncStatus status, Instant now) {
        this.reservationId = reservationId;
        transition(status, null, now);
        if (status == CalendarSyncStatus.PENDING_CREATE) {
            ensureEventKey();
            nextAttemptAt = now;
        }
    }

    public void transition(CalendarSyncStatus status, CalendarFailure error, Instant now) {
        this.status = status;
        lastError = error;
        updatedAt = now;
    }

    public void attachEvent(String eventId) {
        googleEventId = eventId;
    }

    public void ensureEventKey() {
        if (eventKey == null && !createDispatched && googleEventId == null) {
            eventKey = "bc" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    public boolean hasActiveLease(Instant now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }

    public boolean ownsLease(String token) {
        return token != null && token.equals(leaseToken);
    }

    public void claim(boolean create, String token, Instant now, Instant until) {
        attemptCount++;
        leaseToken = token;
        leaseUntil = until;
        lastAttemptAt = now;
        if (create) {
            createDispatched = true;
        }
        transition(create ? CalendarSyncStatus.CREATING : CalendarSyncStatus.DELETING, null, now);
    }

    public void schedule(CalendarSyncStatus status, Instant now) {
        transition(status, null, now);
        attemptCount = 0;
        nextAttemptAt = now;
        clearLease();
    }

    public void complete(CalendarSyncStatus status, Instant now, Instant nextCheck) {
        transition(status, null, now);
        attemptCount = 0;
        nextAttemptAt = nextCheck;
        clearLease();
    }

    public void deferUntil(Instant next) {
        nextAttemptAt = next;
    }

    public void fail(boolean create, CalendarFailure failure, Instant now, Instant nextAttempt) {
        transition(create ? CalendarSyncStatus.CREATE_FAILED : CalendarSyncStatus.DELETE_FAILED, failure, now);
        nextAttemptAt = nextAttempt;
        clearLease();
    }

    private void clearLease() {
        leaseToken = null;
        leaseUntil = null;
    }
}
