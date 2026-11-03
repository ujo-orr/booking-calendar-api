package org.example.github_api_practice.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CalendarSyncService {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);
    private final CalendarSyncStore store;
    private final GoogleCalendarClient client;

    public CalendarSyncService(CalendarSyncStore store, GoogleCalendarClient client) {
        this.store = store;
        this.client = client;
    }

    public void synchronize(Long reservationId) {
        CalendarSyncStore.Work work = null;
        boolean externalSucceeded = false;
        try {
            work = store.claim(reservationId);
            if (work == null) {
                return;
            }
            if (work.create()) {
                String eventId = client.create(work.memberId(), reservationId, work.startsAt(), work.endsAt());
                externalSucceeded = true;
                if (store.created(reservationId, eventId)) {
                    // 생성 중 확정된 취소를 반영하는 후속 삭제이며 생성 재시도가 아닌 처리
                    synchronize(reservationId);
                }
            } else {
                client.delete(work.memberId(), work.eventId());
                externalSucceeded = true;
                store.deleted(reservationId);
            }
        } catch (RuntimeException exception) {
            CalendarFailure failure = exception instanceof CalendarException calendarException
                    ? calendarException.getFailure() : CalendarFailure.INTERNAL_ERROR;
            if (externalSucceeded) {
                failure = CalendarFailure.OUTCOME_UNKNOWN;
            }
            if (work != null) {
                try {
                    store.failed(work, failure);
                } catch (RuntimeException persistenceException) {
                    log.error("Calendar 상태 저장 실패: {}", persistenceException.getClass().getSimpleName());
                }
            }
            // 이미 commit된 예약을 Calendar 오류로 실패 응답처럼 보이지 않게 하는 오류 격리
            log.warn("Calendar 동기화 실패: reservationId={}, code={}", reservationId, failure);
        }
    }
}
