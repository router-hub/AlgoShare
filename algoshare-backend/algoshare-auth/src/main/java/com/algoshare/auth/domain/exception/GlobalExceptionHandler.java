package com.algoshare.auth.domain.exception;

import com.algoshare.auth.api.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for all REST controllers.
 * Maps exceptions to consistent ApiErrorResponse with proper HTTP status codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation errors from @Valid annotations
     * Returns 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Invalid request");

        ApiErrorResponse error = new ApiErrorResponse(
                "VALIDATION_ERROR",
                message,
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    /**
     * Handle duplicate email registration
     * Returns 409 Conflict
     */
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailExists(
            EmailAlreadyExistsException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "EMAIL_ALREADY_EXISTS",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error);
    }

    /**
     * Handle if password and confirm password are not same
     * Return 400 Bad Request
     */
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiErrorResponse> handlePasswordMismatch(
            InvalidInputException ex,
            HttpServletRequest request){
        ApiErrorResponse error = new ApiErrorResponse(
                "PASSWORD_MISMATCH_IN_REQUEST",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    /**
     * Handle invalid login credentials
     * Returns 401 Unauthorized
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "INVALID_CREDENTIALS",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }

    /**
     * Handle account lockout
     * Returns 429 Too Many Requests with Retry-After header
     */
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountLocked(
            AccountLockedException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "ACCOUNT_LOCKED",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(error);
    }

    /**
     * Handle invalid OTP
     * Returns 401 Unauthorized
     */
    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOtp(
            InvalidOtpException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "INVALID_OTP",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }

    /**
     * Handle session expired
     * Returns 401 Unauthorized
     */
    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionExpired(
            SessionExpiredException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "SESSION_EXPIRED",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }

    /**
     * Handle device fingerprint mismatch
     * Returns 401 Unauthorized
     */
    @ExceptionHandler(DeviceMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleDeviceMismatch(
            DeviceMismatchException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "DEVICE_MISMATCH",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }

    /**
     * Handle token expired
     * Returns 401 Unauthorized
     */
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleTokenExpired(
            TokenExpiredException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = new ApiErrorResponse(
                "TOKEN_EXPIRED",
                ex.getMessage(),
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }

    /**
     * Handle all other unexpected exceptions
     * Returns 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(
            Exception ex,
            HttpServletRequest request) {

        // Log the full exception for debugging
        ex.printStackTrace();

        ApiErrorResponse error = new ApiErrorResponse(
                "INTERNAL_ERROR",
                "Something went wrong. Please try again later.",
                request.getHeader("X-Trace-Id")
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }
}
