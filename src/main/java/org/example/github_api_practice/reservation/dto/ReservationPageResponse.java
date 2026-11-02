package org.example.github_api_practice.reservation.dto;

import java.util.List;

public record ReservationPageResponse(List<ReservationResponse> content, int page, int size,
                                      long totalElements, int totalPages) {
}
