package com.banking.payment.exception;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String,Object>> handleAuth(UnauthorizedException ex, HttpServletRequest req) {
        log.error("mTLS/auth failure: {}", ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), req);
    }
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String,Object>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), req);
    }
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String,Object>> handleUnavailable(ServiceUnavailableException ex, HttpServletRequest req) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), req);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage(), req);
    }
    private ResponseEntity<Map<String,Object>> error(HttpStatus s, String code, String msg, HttpServletRequest req) {
        return ResponseEntity.status(s).body(Map.of("error", code, "message", msg,
            "path", req.getRequestURI(), "timestamp", LocalDateTime.now().toString()));
    }
}
