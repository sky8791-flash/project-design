package com.collabdoc.controller;

import com.collabdoc.exception.ConflictException;
import com.collabdoc.exception.ForbiddenException;
import com.collabdoc.exception.InvalidCredentialsException;
import com.collabdoc.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Single place where a service exception becomes an HTTP status. Services throw
 * {@code NotFound}/{@code Forbidden}/{@code Conflict}/plain {@code IllegalArgument|IllegalState}, and the
 * handlers no longer repeat the mapping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message(e)));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message(e)));
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<Map<String, Object>> forbidden(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", message(e)));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", message(e), "version", e.getCurrentVersion()));
    }

    /**
     * {@code Throwable} rather than {@code RuntimeException}: two of these types
     * ({@code MissingServletRequestParameterException}, {@code MethodArgumentNotValidException}) are checked,
     * and a parameter the handler cannot accept makes Spring log "Failure in @ExceptionHandler" and fall back
     * to Boot's default error body — the status would still be 400, so no test catches it.
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            MissingServletRequestParameterException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Throwable e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    /**
     * Spring MVC's own signals must keep the status they carry. A mistyped id, a wrong verb, an unknown
     * path or an unreadable body all surface as one of these; the catch-all below would otherwise turn a
     * 404 into a 500 and make a bad URL look like a server fault.
     */
    @ExceptionHandler({ErrorResponseException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> mvcError(Throwable e) {
        // @ExceptionHandler cannot take the ErrorResponse interface (it is not a Throwable), so the
        // status is read per exception at runtime and anything left over is the client's fault.
        HttpStatusCode status = e instanceof ErrorResponse error
                ? error.getStatusCode()
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", message(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Unexpected server error"));
    }

    private String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
