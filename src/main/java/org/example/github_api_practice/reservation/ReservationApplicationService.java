package org.example.github_api_practice.reservation;

import org.example.github_api_practice.calendar.CalendarSyncService;
import org.example.github_api_practice.reservation.dto.ReservationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReservationApplicationService {
    private final ReservationService reservations;
    private final CalendarSyncService calendar;

    public ReservationApplicationService(ReservationService reservations, CalendarSyncService calendar) {
        this.reservations = reservations;
        this.calendar = calendar;
    }

    public ReservationResponse reserve(String subject, Long slotId) {
        ReservationResponse result = reservations.reserve(subject, slotId);
        // 예약 트랜잭션 commit 이후 별도 DB 작업과 HTTP 호출 수행
        calendar.synchronize(result.reservationId());
        return result;
    }

    public ReservationResponse cancel(String subject, Long reservationId) {
        ReservationResponse result = reservations.cancel(subject, reservationId);
        calendar.synchronize(reservationId);
        return result;
    }
}
