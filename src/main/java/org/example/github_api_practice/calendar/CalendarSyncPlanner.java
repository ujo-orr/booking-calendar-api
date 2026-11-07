package org.example.github_api_practice.calendar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CalendarSyncPlanner {
    private final CalendarConnectionRepository connections;
    private final CalendarSyncRepository syncs;
    private final CalendarTokenCipher cipher;
    private final Clock clock;

    public CalendarSyncPlanner(CalendarConnectionRepository connections, CalendarSyncRepository syncs,
                               CalendarTokenCipher cipher, Clock clock) {
        this.connections = connections;
        this.syncs = syncs;
        this.cipher = cipher;
        this.clock = clock;
    }

    public void reserved(Long reservationId, Long memberId) {
        boolean connected = cipher.isConfigured() && connections.findById(memberId)
                .filter(connection -> !connection.isReauthRequired()).isPresent();
        syncs.saveAndFlush(new ReservationCalendarSync(reservationId, connected
                ? CalendarSyncStatus.PENDING_CREATE : CalendarSyncStatus.NOT_CONNECTED, clock.instant()));
    }

    public void cancelled(Long reservationId) {
        ReservationCalendarSync sync = syncs.findById(reservationId).orElseThrow();
        switch (sync.getStatus()) {
            case NOT_CONNECTED -> sync.complete(CalendarSyncStatus.DELETED, clock.instant(), null);
            case PENDING_CREATE -> {
                if (sync.isCreateDispatched()) {
                    sync.schedule(CalendarSyncStatus.PENDING_DELETE, clock.instant());
                } else {
                    sync.complete(CalendarSyncStatus.DELETED, clock.instant(), null);
                }
            }
            case SYNCED, DELETE_FAILED -> sync.schedule(CalendarSyncStatus.PENDING_DELETE, clock.instant());
            case CREATE_FAILED -> sync.schedule(CalendarSyncStatus.PENDING_DELETE, clock.instant());
            // 생성 중 취소는 생성 결과를 저장하는 쪽에서 삭제로 연결하는 상태 유지
            default -> { }
        }
        syncs.flush();
    }
}
