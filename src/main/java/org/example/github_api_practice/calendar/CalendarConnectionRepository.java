package org.example.github_api_practice.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CalendarConnection c where c.memberId = :id")
    Optional<CalendarConnection> findLockedById(@Param("id") Long id);
}
