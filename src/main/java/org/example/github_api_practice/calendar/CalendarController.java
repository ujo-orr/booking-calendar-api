package org.example.github_api_practice.calendar;

import org.example.github_api_practice.calendar.dto.CalendarConnectionResponse;
import org.example.github_api_practice.calendar.dto.CalendarSyncResponse;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.global.response.ApiResponse;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;

@RestController
public class CalendarController {
    private final CalendarConnectionStore connections;
    private final CalendarSyncStore syncs;
    private final MemberService members;

    public CalendarController(CalendarConnectionStore connections, CalendarSyncStore syncs, MemberService members) {
        this.connections = connections;
        this.syncs = syncs;
        this.members = members;
    }

    @GetMapping("/api/calendar/connection")
    public ApiResponse<CalendarConnectionResponse> connection(@AuthenticationPrincipal OidcUser user) {
        return ApiResponse.success(connections.status(members.findByIdentity(AuthProvider.GOOGLE, user.getSubject()).getId()));
    }

    @GetMapping("/api/reservations/{reservationId}/calendar")
    public ApiResponse<CalendarSyncResponse> status(@AuthenticationPrincipal OidcUser user, @PathVariable Long reservationId) {
        if (reservationId < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(syncs.status(reservationId,
                members.findByIdentity(AuthProvider.GOOGLE, user.getSubject()).getId()));
    }

    @PostMapping("/api/reservations/{reservationId}/calendar/retry")
    public ResponseEntity<ApiResponse<CalendarSyncResponse>> retry(@AuthenticationPrincipal OidcUser user,
                                                                  @PathVariable Long reservationId) {
        if (reservationId < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Long memberId = members.findByIdentity(AuthProvider.GOOGLE, user.getSubject()).getId();
        return ResponseEntity.accepted().body(ApiResponse.success(syncs.requestRetry(reservationId, memberId)));
    }
}
