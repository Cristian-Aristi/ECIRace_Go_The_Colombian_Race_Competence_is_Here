package edu.eci.arsw.eciracego.security;

import org.springframework.http.HttpStatus;

/**
 * Error de la API con su código HTTP y un mensaje apto para mostrar al usuario.
 * {@code code} es un identificador estable que el front puede usar para decidir qué hacer
 * (por ejemplo USERNAME_REQUIRED para pedir el tag del piloto).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Debes iniciar sesión.");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
