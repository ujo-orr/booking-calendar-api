package org.example.github_api_practice.global.response;

import org.example.github_api_practice.global.error.ErrorCode;

public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "success", data);
    }

    public static ApiResponse<ErrorDetail> failure(ErrorCode errorCode) {
        return failure(errorCode.name(), errorCode.getMessage());
    }

    public static ApiResponse<ErrorDetail> failure(String code, String message) {
        return new ApiResponse<>(false, message, new ErrorDetail(code));
    }

    public record ErrorDetail(String code) {
    }
}
