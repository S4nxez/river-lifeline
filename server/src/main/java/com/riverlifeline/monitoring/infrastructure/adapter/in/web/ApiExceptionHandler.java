package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.exception.CommunityNotFoundException;
import com.riverlifeline.monitoring.domain.exception.EarlyWarningNotFoundException;
import com.riverlifeline.monitoring.domain.exception.LayerNotFoundException;
import com.riverlifeline.monitoring.domain.exception.PassNotFoundException;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ErrorResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every failure as {@code {"error": {"code", "message", "details"}}} (CONTRACTS.md 3.2). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ErrorResponse> invalidParameter(InvalidParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage(), Map.of("parameter", e.parameter()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage(), null);
    }

    @ExceptionHandler(AoiNotFoundException.class)
    ResponseEntity<ErrorResponse> aoiNotFound(AoiNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "aoi_not_found", e.getMessage(), null);
    }

    @ExceptionHandler(PassNotFoundException.class)
    ResponseEntity<ErrorResponse> passNotFound(PassNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "pass_not_found", e.getMessage(), null);
    }

    @ExceptionHandler(CommunityNotFoundException.class)
    ResponseEntity<ErrorResponse> communityNotFound(CommunityNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "community_not_found", e.getMessage(), null);
    }

    @ExceptionHandler(LayerNotFoundException.class)
    ResponseEntity<ErrorResponse> layerNotFound(LayerNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "layer_not_found", e.getMessage(), null);
    }

    @ExceptionHandler(EarlyWarningNotFoundException.class)
    ResponseEntity<ErrorResponse> earlyWarningNotFound(EarlyWarningNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "early_warning_not_found", e.getMessage(), null);
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<ErrorResponse> catalogUnavailable(CatalogUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable", e.getMessage(), null);
    }

    @ExceptionHandler(CatalogSchemaUnsupportedException.class)
    ResponseEntity<ErrorResponse> catalogSchemaUnsupported(CatalogSchemaUnsupportedException e) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "catalog_schema_unsupported",
                e.getMessage(),
                Map.of("schema_version", e.found().toString()));
    }

    /** Not in the contract's table: a route that doesn't exist at all. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorResponse> noRoute(Exception e) {
        return error(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.", null);
    }

    /** Not in the contract's table: the API is read-only (GET only). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "The API is read-only: use GET.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internalError(Exception e) {
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected error.", null);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Object details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(ApiPaths.JSON))
                .body(ErrorResponse.of(code, message, details));
    }
}
