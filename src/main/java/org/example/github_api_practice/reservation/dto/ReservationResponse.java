package org.example.github_api_practice.reservation.dto;

import org.example.github_api_practice.reservation.Reservation;
import org.example.github_api_practice.reservation.ReservationStatus;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ReservationResponse(Long reservationId, Long slotId, ReservationStatus status,
                                  OffsetDateTime startsAt, OffsetDateTime endsAt,
                                  OffsetDateTime createdAt, OffsetDateTime cancelledAt) {

    public static ReservationResponse from(Reservation reservation, ZoneId zone) {
        return new ReservationResponse(reservation.getId(), reservation.getSlot().getId(), reservation.getStatus(),
                reservation.getSlot().getStartsAt().atZone(zone).toOffsetDateTime(),
                reservation.getSlot().getEndsAt().atZone(zone).toOffsetDateTime(),
                reservation.getCreatedAt().atZone(zone).toOffsetDateTime(),
                reservation.getCancelledAt() == null ? null
                        : reservation.getCancelledAt().atZone(zone).toOffsetDateTime());
    }
}
