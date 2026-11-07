package org.example.github_api_practice.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
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
        // 한 번의 진입에서 생성 뒤 취소 반영까지 처리하되 실패 재시도는 DB 일정으로 분리
        for (int phase = 0; phase < 2; phase++) {
            CalendarSyncStore.Work work = null;
            boolean externalSucceeded = false;
            try {
                work = store.claim(reservationId);
                if (work == null) {
                    return;
                }
                if (work.create()) {
                    String id = client.ensureCreated(work);
                    externalSucceeded = true;
                    if (!store.created(work, id)) {
                        return;
                    }
                } else {
                    client.ensureDeleted(work);
                    externalSucceeded = true;
                    store.deleted(work);
                    return;
                }
            } catch (RuntimeException exception) {
                CalendarException failure = exception instanceof CalendarException calendarException ? calendarException
                        : new CalendarException(externalSucceeded ? CalendarFailure.OUTCOME_UNKNOWN
                        : exception instanceof TransientDataAccessException ? CalendarFailure.LOCAL_CONFLICT : CalendarFailure.INTERNAL_ERROR);
                boolean followCancellation = false;
                if (work != null) {
                    try {
                        followCancellation = store.failed(work, failure);
                    } catch (RuntimeException persistenceException) {
                        log.error("Calendar 상태 저장 실패: {}", persistenceException.getClass().getSimpleName());
                    }
                }
                log.warn("Calendar 동기화 실패: reservationId={}, code={}", reservationId, failure.getFailure());
                if (!followCancellation) {
                    return;
                }
            }
        }
    }
}
