package com.privacypilot.web;

import com.privacypilot.workflow.InvalidTransitionException;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts exceptions into clean {@link ApiError} responses instead of leaking
 * stack traces to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(
                ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed",
                        "One or more fields are invalid.", details));
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(InvalidTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(HttpStatus.CONFLICT.value(), "Invalid workflow transition",
                        ex.getMessage(), List.of()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(HttpStatus.NOT_FOUND.value(), "Not found", ex.getMessage(), List.of()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleConcurrentModification(Exception ex) {
        // Two concurrent transitions collided; the losing writer is asked to retry
        // on fresh state rather than silently overwriting the winner.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(HttpStatus.CONFLICT.value(), "Concurrent modification",
                        "The request was modified concurrently; retry with the latest state.", List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad request", ex.getMessage(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal error",
                        "An unexpected error occurred.", List.of()));
    }
}
