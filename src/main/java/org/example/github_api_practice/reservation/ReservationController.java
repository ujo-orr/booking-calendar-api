package org.example.github_api_practice.reservation;

import org.example.github_api_practice.global.response.ApiResponse;
import org.example.github_api_practice.reservation.dto.CreateReservationRequest;
import org.example.github_api_practice.reservation.dto.ReservationPageResponse;
import org.example.github_api_practice.reservation.dto.ReservationResponse;
import org.example.github_api_practice.reservation.dto.ReservationSlotResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/api/reservation-slots")
    public ApiResponse<List<ReservationSlotResponse>> slots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(reservationService.availableSlots(date));
    }

    @GetMapping("/api/reservation-slots/dates")
    public ApiResponse<List<LocalDate>> dates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(reservationService.availableDates(from, to));
    }

    @PostMapping("/api/reservations")
    public ResponseEntity<ApiResponse<ReservationResponse>> reserve(@AuthenticationPrincipal OidcUser user,
                                                                   @RequestBody CreateReservationRequest request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.success(reservationService.reserve(user.getSubject(), request.slotId())));
    }

    @GetMapping("/api/reservations")
    public ApiResponse<ReservationPageResponse> mine(@AuthenticationPrincipal OidcUser user,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(reservationService.mine(user.getSubject(), page, size));
    }

    @PostMapping("/api/reservations/{reservationId}/cancel")
    public ApiResponse<ReservationResponse> cancel(@AuthenticationPrincipal OidcUser user,
                                                   @PathVariable Long reservationId) {
        return ApiResponse.success(reservationService.cancel(user.getSubject(), reservationId));
    }
}
