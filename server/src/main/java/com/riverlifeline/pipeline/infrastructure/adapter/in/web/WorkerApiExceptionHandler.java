package com.riverlifeline.pipeline.infrastructure.adapter.in.web;

import com.riverlifeline.pipeline.application.exception.InvalidRequestException;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.exception.NotLeasedException;
import com.riverlifeline.pipeline.application.exception.RasterTooLargeException;
import java.util.zip.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The worker API's answers to failures, as the contract lists them: 400 malformed request, 409 not leased any more,
 * 413 raster too large, 422 result that does not fit its job. Body: {@code {"error": message}}.
 */
@RestControllerAdvice(assignableTypes = WorkerApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "river.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerApiExceptionHandler.class);

    @ExceptionHandler(NotLeasedException.class)
    ResponseEntity<String> notLeased(NotLeasedException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidResultException.class)
    ResponseEntity<String> invalidResult(InvalidResultException e) {
        log.warn("Result refused: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(RasterTooLargeException.class)
    ResponseEntity<String> tooLarge(RasterTooLargeException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler({
        InvalidRequestException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        ZipException.class
    })
    ResponseEntity<String> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception e) {
        log.error("Worker API failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    static ResponseEntity<String> error(HttpStatus status, String message) {
        String text = message == null ? status.getReasonPhrase() : message;
        String body = "{\"error\":\""
                + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
