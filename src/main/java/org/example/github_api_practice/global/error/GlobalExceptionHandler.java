package org.example.github_api_practice.global.error;

import org.example.github_api_practice.global.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.TransactionException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleApiException(ApiException exception) {
        return error(exception.getErrorCode());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleConflict() {
        return error(ErrorCode.DATA_CONFLICT);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleInvalidRequest() {
        return error(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleLockConflict() {
        return error(ErrorCode.RESERVATION_CONFLICT);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleDatabaseException() {
        return error(ErrorCode.DATABASE_UNAVAILABLE);
    }

    @ExceptionHandler({ErrorResponseException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleHttpException(Exception exception) {
        ErrorResponse errorResponse = (ErrorResponse) exception;
        HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
        return ResponseEntity.status(status).headers(errorResponse.getHeaders()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure("HTTP_" + status.value(), status.getReasonPhrase()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> handleUnexpectedException(Exception exception) {
        log.error("API 처리 실패: {}", exception.getClass().getSimpleName());
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<ApiResponse.ErrorDetail>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.failure(errorCode));
    }
}
