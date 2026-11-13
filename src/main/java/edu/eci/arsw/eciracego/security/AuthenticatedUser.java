package edu.eci.arsw.eciracego.security;

import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.Optional;

/**
 * Usuario autenticado de una petición REST o de una sesión STOMP.
 * El nombre SIEMPRE sale del token de sesión validado por el servidor, nunca del cuerpo de la petición.
 */
public record AuthenticatedUser(String username) implements Principal {

    /** Atributo de la petición donde {@link AuthTokenFilter} deja al usuario autenticado. */
    public static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();

    /** Cabecera con la clave secreta del host de una sala (la entrega POST /api/rooms/create). */
    public static final String HOST_KEY_HEADER = "X-Host-Key";

    @Override
    public String getName() {
        return username;
    }

    public static Optional<String> from(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof AuthenticatedUser user ? Optional.of(user.username()) : Optional.empty();
    }

    public static String require(HttpServletRequest request) {
        return from(request).orElseThrow(ApiException::unauthorized);
    }

    public static String hostKey(HttpServletRequest request) {
        return request.getHeader(HOST_KEY_HEADER);
    }
}
