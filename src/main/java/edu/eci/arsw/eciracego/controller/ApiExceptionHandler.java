package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores de la API como JSON {error, code} con el código HTTP correcto (antes: 500 genérico). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return body(e.getStatus(), e.getMessage(), e.getCode());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        log.debug("Petición inválida: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Solicitud inválida.", "BAD_REQUEST");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message, String code) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("error", message);
        if (code != null) {
            map.put("code", code);
        }
        return ResponseEntity.status(status).body(map);
    }
}
