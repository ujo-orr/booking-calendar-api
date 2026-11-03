package org.example.github_api_practice.calendar;

import org.example.github_api_practice.calendar.dto.CalendarSyncResponse;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.reservation.Reservation;
import org.example.github_api_practice.reservation.ReservationRepository;
import org.example.github_api_practice.reservation.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CalendarSyncStore {
    private final ReservationRepository reservations;
    private final CalendarSyncRepository syncs;
    private final Clock clock;

    public CalendarSyncStore(ReservationRepository reservations, CalendarSyncRepository syncs, Clock clock) {
        this.reservations = reservations;
        this.syncs = syncs;
        this.clock = clock;
    }

    public Work claim(Long reservationId) {
        Reservation reservation = locked(reservationId);
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        boolean create = sync.getStatus() == CalendarSyncStatus.PENDING_CREATE;
        boolean delete = sync.getStatus() == CalendarSyncStatus.PENDING_DELETE;
        if (!create && !delete) {
            return null;
        }
        if (create && reservation.getStatus() == ReservationStatus.CANCELLED) {
            sync.transition(CalendarSyncStatus.DELETED, null, clock.instant());
            return null;
        }
        sync.transition(create ? CalendarSyncStatus.CREATING : CalendarSyncStatus.DELETING, null, clock.instant());
        return new Work(reservationId, reservation.getMember().getId(), create, sync.getGoogleEventId(),
                reservation.getSlot().getStartsAt(), reservation.getSlot().getEndsAt());
    }

    public boolean created(Long reservationId, String eventId) {
        Reservation reservation = locked(reservationId);
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        sync.attachEvent(eventId);
        boolean cancelled = reservation.getStatus() == ReservationStatus.CANCELLED;
        sync.transition(cancelled ? CalendarSyncStatus.PENDING_DELETE : CalendarSyncStatus.SYNCED, null, clock.instant());
        return cancelled;
    }

    public void deleted(Long reservationId) {
        locked(reservationId);
        syncs.findById(reservationId).orElseThrow().transition(CalendarSyncStatus.DELETED, null, clock.instant());
    }

    public void failed(Work work, CalendarFailure failure) {
        locked(work.reservationId());
        syncs.findById(work.reservationId()).orElseThrow().transition(work.create()
                ? CalendarSyncStatus.CREATE_FAILED : CalendarSyncStatus.DELETE_FAILED, failure, clock.instant());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public CalendarSyncResponse status(Long reservationId, Long memberId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getMember().getId().equals(memberId)) {
            throw new ApiException(ErrorCode.CALENDAR_SYNC_FORBIDDEN);
        }
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        return new CalendarSyncResponse(reservationId, sync.getStatus(), sync.getGoogleEventId(),
                sync.getLastError(), sync.getUpdatedAt());
    }

    private Reservation locked(Long id) {
        return reservations.findLockedById(id).orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    public record Work(Long reservationId, Long memberId, boolean create, String eventId,
                       Instant startsAt, Instant endsAt) {
    }
}
