package org.example.github_api_practice.reservation.dto;

import org.example.github_api_practice.reservation.ReservationSlot;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ReservationSlotResponse(Long slotId, OffsetDateTime startsAt, OffsetDateTime endsAt) {

    public static ReservationSlotResponse from(ReservationSlot slot, ZoneId zone) {
        return new ReservationSlotResponse(slot.getId(), slot.getStartsAt().atZone(zone).toOffsetDateTime(),
                slot.getEndsAt().atZone(zone).toOffsetDateTime());
    }
}
