package org.example.github_api_practice.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, Long> {
}
