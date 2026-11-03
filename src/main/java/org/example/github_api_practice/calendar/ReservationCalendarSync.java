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

    public ReservationCalendarSync(Long reservationId, CalendarSyncStatus status, Instant now) {
        this.reservationId = reservationId;
        transition(status, null, now);
    }

    public void transition(CalendarSyncStatus status, CalendarFailure error, Instant now) {
        this.status = status;
        lastError = error;
        updatedAt = now;
    }

    public void attachEvent(String eventId) {
        googleEventId = eventId;
    }
}
