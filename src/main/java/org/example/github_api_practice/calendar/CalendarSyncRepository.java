package org.example.github_api_practice.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CalendarSyncRepository extends JpaRepository<ReservationCalendarSync, Long> {
    @Query("""
            select s.reservationId from ReservationCalendarSync s
            where s.nextAttemptAt <= :now and (s.leaseUntil is null or s.leaseUntil <= :now)
            order by s.nextAttemptAt, s.reservationId
            """)
    List<Long> findDue(@Param("now") Instant now, Pageable pageable);
}
