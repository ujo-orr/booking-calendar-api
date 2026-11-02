package org.example.github_api_practice.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationSlotRepository extends JpaRepository<ReservationSlot, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReservationSlot s where s.id = :id")
    Optional<ReservationSlot> findLockedById(@Param("id") Long id);

    @Query("""
            select s from ReservationSlot s
            where s.startsAt >= :from and s.startsAt < :to and s.startsAt > :now
              and not exists (select r.id from Reservation r where r.activeSlotId = s.id)
            order by s.startsAt, s.id
            """)
    List<ReservationSlot> findAvailable(@Param("from") Instant from, @Param("to") Instant to,
                                        @Param("now") Instant now);
}
