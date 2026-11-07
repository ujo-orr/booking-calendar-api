package org.example.github_api_practice.calendar;

import org.example.github_api_practice.calendar.dto.CalendarSyncResponse;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.reservation.Reservation;
import org.example.github_api_practice.reservation.ReservationRepository;
import org.example.github_api_practice.reservation.ReservationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class CalendarSyncStore {
    private final ReservationRepository reservations;
    private final CalendarSyncRepository syncs;
    private final CalendarConnectionRepository connections;
    private final CalendarTokenCipher cipher;
    private final CalendarSyncProperties properties;
    private final CalendarRetryPolicy retries;
    private final Clock clock;

    public CalendarSyncStore(ReservationRepository reservations, CalendarSyncRepository syncs,
                             CalendarConnectionRepository connections, CalendarTokenCipher cipher,
                             CalendarSyncProperties properties, CalendarRetryPolicy retries, Clock clock) {
        this.reservations = reservations;
        this.syncs = syncs;
        this.connections = connections;
        this.cipher = cipher;
        this.properties = properties;
        this.retries = retries;
        this.clock = clock;
    }

    public Work claim(Long reservationId) {
        Reservation reservation = locked(reservationId);
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        Instant now = clock.instant();
        if (sync.getNextAttemptAt() == null || sync.getNextAttemptAt().isAfter(now) || sync.hasActiveLease(now)) {
            return null;
        }
        boolean create = reservation.getStatus() == ReservationStatus.CONFIRMED;
        if (!create && sync.getStatus() != CalendarSyncStatus.PENDING_DELETE
                && sync.getStatus() != CalendarSyncStatus.DELETING && sync.getStatus() != CalendarSyncStatus.DELETE_FAILED
                && sync.getStatus() != CalendarSyncStatus.DELETED) {
            sync.schedule(CalendarSyncStatus.PENDING_DELETE, now);
        }
        if (create) {
            sync.ensureEventKey();
        }
        String target = sync.getGoogleEventId() != null ? sync.getGoogleEventId() : sync.getEventKey();
        if (target == null) {
            if (!create && !sync.isCreateDispatched()) {
                sync.complete(CalendarSyncStatus.DELETED, now, null);
            } else {
                // 이전 버전에서 ID 없이 전송한 생성 요청의 결과를 추측하지 않는 복구 중단
                sync.fail(create, CalendarFailure.LEGACY_OUTCOME_UNKNOWN, now, null);
            }
            return null;
        }
        if (sync.getAttemptCount() >= properties.maxAttempts()) {
            sync.fail(create, sync.getLastError() == null ? CalendarFailure.OUTCOME_UNKNOWN : sync.getLastError(), now, null);
            return null;
        }
        String token = UUID.randomUUID().toString();
        sync.claim(create, token, now, now.plus(properties.leaseDuration()));
        return new Work(reservationId, reservation.getMember().getId(), create, target,
                reservation.getSlot().getStartsAt(), reservation.getSlot().getEndsAt(),
                sync.getEventKey(), token, sync.getGoogleEventId() != null);
    }

    public boolean created(Work work, String eventId) {
        Reservation reservation = locked(work.reservationId());
        ReservationCalendarSync sync = syncs.findById(work.reservationId()).orElseThrow();
        if (!sync.ownsLease(work.leaseToken())) {
            return false;
        }
        sync.attachEvent(eventId);
        boolean cancelled = reservation.getStatus() == ReservationStatus.CANCELLED;
        if (cancelled) {
            sync.schedule(CalendarSyncStatus.PENDING_DELETE, clock.instant());
        } else {
            sync.complete(CalendarSyncStatus.SYNCED, clock.instant(), clock.instant().plus(properties.reconcileInterval()));
        }
        return cancelled;
    }

    public void deleted(Work work) {
        locked(work.reservationId());
        ReservationCalendarSync sync = syncs.findById(work.reservationId()).orElseThrow();
        if (sync.ownsLease(work.leaseToken())) {
            // 늦게 완료되는 외부 생성도 다시 확인할 수 있도록 유지하는 정기 검증 일정
            sync.complete(CalendarSyncStatus.DELETED, clock.instant(), clock.instant().plus(properties.reconcileInterval()));
        }
    }

    public boolean failed(Work work, CalendarException exception) {
        Reservation reservation = locked(work.reservationId());
        ReservationCalendarSync sync = syncs.findById(work.reservationId()).orElseThrow();
        if (!sync.ownsLease(work.leaseToken())) {
            return false;
        }
        Instant now = clock.instant();
        if (work.create() && reservation.getStatus() == ReservationStatus.CANCELLED) {
            sync.schedule(CalendarSyncStatus.PENDING_DELETE, now);
            if (exception.getFailure() == CalendarFailure.RATE_LIMITED) {
                sync.deferUntil(now.plus(retries.delay(1, exception.getRetryAfter())));
                return false;
            }
            return true;
        }
        Instant next = exception.getFailure().retryable() && sync.getAttemptCount() < properties.maxAttempts()
                ? now.plus(retries.delay(sync.getAttemptCount(), exception.getRetryAfter())) : null;
        sync.fail(work.create(), exception.getFailure(), now, next);
        return false;
    }

    public CalendarSyncResponse requestRetry(Long reservationId, Long memberId) {
        Reservation reservation = locked(reservationId);
        requireOwner(reservation, memberId);
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        if (sync.hasActiveLease(clock.instant())) {
            throw new ApiException(ErrorCode.CALENDAR_SYNC_BUSY);
        }
        if (!cipher.isConfigured()) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONFIGURED);
        }
        if (connections.findById(memberId).filter(connection -> !connection.isReauthRequired()).isEmpty()) {
            throw new ApiException(ErrorCode.CALENDAR_PERMISSION_REQUIRED);
        }
        if (sync.getEventKey() == null && sync.getGoogleEventId() == null && sync.isCreateDispatched()) {
            throw new ApiException(ErrorCode.CALENDAR_RECOVERY_REQUIRED);
        }
        boolean create = reservation.getStatus() == ReservationStatus.CONFIRMED;
        if (create) {
            sync.ensureEventKey();
        }
        sync.schedule(create ? CalendarSyncStatus.PENDING_CREATE : CalendarSyncStatus.PENDING_DELETE, clock.instant());
        return response(sync);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> dueIds() {
        return syncs.findDue(clock.instant(), PageRequest.of(0, properties.batchSize()));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public CalendarSyncResponse status(Long reservationId, Long memberId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        requireOwner(reservation, memberId);
        return response(syncs.findById(reservationId).orElseThrow());
    }

    private CalendarSyncResponse response(ReservationCalendarSync sync) {
        boolean exhausted = sync.getLastError() != null && sync.getLastError().retryable()
                && sync.getNextAttemptAt() == null && sync.getAttemptCount() >= properties.maxAttempts();
        return new CalendarSyncResponse(sync.getReservationId(), sync.getStatus(), sync.getGoogleEventId(),
                sync.getLastError(), sync.getUpdatedAt(), sync.getAttemptCount(), properties.maxAttempts(),
                sync.getNextAttemptAt(), sync.getLeaseUntil(), exhausted);
    }

    private void requireOwner(Reservation reservation, Long memberId) {
        if (!reservation.getMember().getId().equals(memberId)) {
            throw new ApiException(ErrorCode.CALENDAR_SYNC_FORBIDDEN);
        }
    }

    private Reservation locked(Long id) {
        return reservations.findLockedById(id).orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    public record Work(Long reservationId, Long memberId, boolean create, String eventId,
                       Instant startsAt, Instant endsAt, String eventKey, String leaseToken, boolean knownEvent) {
    }
}
