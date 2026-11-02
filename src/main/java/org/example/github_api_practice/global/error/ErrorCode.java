package org.example.github_api_practice.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "요청 권한이 없거나 CSRF 토큰이 유효하지 않습니다."),
    OAUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "Google 로그인에 실패했습니다. 다시 시도해 주세요."),
    INVALID_OIDC_PROFILE(HttpStatus.UNAUTHORIZED, "Google 사용자 정보가 유효하지 않습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "등록된 회원을 찾을 수 없습니다."),
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
