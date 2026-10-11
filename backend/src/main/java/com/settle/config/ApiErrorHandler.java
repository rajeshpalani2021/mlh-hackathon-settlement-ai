package com.settle.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** JSON errors without stack traces. */
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof ErrorResponse er) { // Spring MVC errors (404, 400, 405...) keep their status
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", String.valueOf(e.getMessage())));
        }
        log.error("Request failed", e);
        return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
