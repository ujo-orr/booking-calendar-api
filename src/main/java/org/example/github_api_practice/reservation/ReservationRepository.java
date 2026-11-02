package org.example.github_api_practice.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByActiveSlotId(Long slotId);

    @Query("select r.slot.id from Reservation r where r.id = :id")
    Optional<Long> findSlotIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findLockedById(@Param("id") Long id);

    @Query(value = """
            select r from Reservation r join fetch r.slot
            where r.member.id = :memberId order by r.createdAt desc, r.id desc
            """, countQuery = "select count(r) from Reservation r where r.member.id = :memberId")
    Page<Reservation> findMine(@Param("memberId") Long memberId, Pageable pageable);
}
