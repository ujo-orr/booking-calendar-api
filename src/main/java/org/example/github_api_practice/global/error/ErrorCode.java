package org.example.github_api_practice.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "요청 권한이 없거나 CSRF 토큰이 유효하지 않습니다."),
    OAUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "Google 로그인에 실패했습니다. 다시 시도해 주세요."),
    INVALID_OIDC_PROFILE(HttpStatus.UNAUTHORIZED, "Google 사용자 정보가 유효하지 않습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "등록된 회원을 찾을 수 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "예약 슬롯을 찾을 수 없습니다."),
    SLOT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 슬롯입니다."),
    SLOT_NOT_BOOKABLE(HttpStatus.CONFLICT, "이미 시작된 슬롯은 예약할 수 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 예약만 취소할 수 있습니다."),
    RESERVATION_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    RESERVATION_CANCELLATION_CLOSED(HttpStatus.CONFLICT, "이미 시작된 예약은 취소할 수 없습니다."),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT, "예약 처리 중 충돌했습니다. 다시 시도해 주세요."),
    CALENDAR_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "캘린더 연동 설정이 필요합니다."),
    CALENDAR_CONNECTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "캘린더 연결 정보를 저장할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    CALENDAR_CONNECTION_FAILED(HttpStatus.BAD_REQUEST, "캘린더 연결에 실패했습니다. 다시 동의해 주세요."),
    CALENDAR_ACCOUNT_MISMATCH(HttpStatus.FORBIDDEN, "로그인한 Google 계정으로 캘린더를 연결해 주세요."),
    CALENDAR_PERMISSION_REQUIRED(HttpStatus.FORBIDDEN, "캘린더 접근 권한에 동의해 주세요."),
    CALENDAR_SYNC_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 캘린더 동기화 정보만 조회할 수 있습니다."),
    CALENDAR_SYNC_BUSY(HttpStatus.CONFLICT, "캘린더 동기화가 처리 중입니다. 잠시 후 다시 확인해 주세요."),
    CALENDAR_RECOVERY_REQUIRED(HttpStatus.CONFLICT, "이전 일정 생성 결과를 확인한 뒤 복구해야 합니다."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "저장된 데이터와 충돌하는 요청입니다."),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "회원 저장소를 사용할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 요청을 처리하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
