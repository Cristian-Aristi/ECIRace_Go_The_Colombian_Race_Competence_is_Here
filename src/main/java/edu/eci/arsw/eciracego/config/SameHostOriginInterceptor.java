package edu.eci.arsw.eciracego.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Control de origen del handshake del WebSocket.
 * <p>
 * El chequeo "mismo origen" de Spring compara esquema y puerto además del host. Detrás de un túnel HTTPS
 * (ngrok, devtunnels, cloudflared) el servidor se ve a sí mismo en http y el navegador manda
 * "Origin: https://..." -> 403 en el handshake. El REST seguía funcionando y el corredor se quedaba
 * en "RECONECTANDO..." para siempre (pruebas con celulares, Sprint 9).
 * <p>
 * Ahora se acepta el handshake si:
 * <ul>
 *   <li>no trae Origin (clientes que no son navegador: no aplica la protección CSWSH), o</li>
 *   <li>el host del Origin es el mismo host que pidió el navegador (cabecera Host, o X-Forwarded-Host si
 *       un proxy/túnel la reescribió), sin importar http/https ni el puerto, o</li>
 *   <li>el Origin está en ECIRACE_ALLOWED_ORIGINS.</li>
 * </ul>
 * Una página de otro sitio sigue sin poder abrir el WebSocket: su Origin es otro host, y desde JavaScript
 * no se pueden poner las cabeceras Host ni X-Forwarded-Host en un handshake. Además la sesión viaja en
 * el CONNECT de STOMP (no en cookies), así que el origen es una defensa extra.
 */
public class SameHostOriginInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SameHostOriginInterceptor.class);

    private final CorsConfiguration allowed;

    public SameHostOriginInterceptor(String[] allowedOriginPatterns) {
        this.allowed = new CorsConfiguration();
        this.allowed.setAllowedOriginPatterns(Arrays.asList(allowedOriginPatterns));
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || isAllowed(origin, request.getHeaders())) {
            return true;
        }
        log.info("WebSocket rechazado: Origin {} no coincide con el host {}", origin, request.getHeaders().getFirst(HttpHeaders.HOST));
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    boolean isAllowed(String origin, HttpHeaders headers) {
        if (allowed.checkOrigin(origin) != null) {
            return true;
        }
        String originHost = hostOfOrigin(origin);
        if (originHost == null) {
            return false;
        }
        return originHost.equals(hostWithoutPort(headers.getFirst(HttpHeaders.HOST)))
                || originHost.equals(hostWithoutPort(firstValue(headers.getFirst("X-Forwarded-Host"))));
    }

    static String hostOfOrigin(String origin) {
        try {
            String host = URI.create(origin.trim()).getHost();
            return host == null ? null : stripBrackets(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "ejemplo.com:8080" -> "ejemplo.com"; "[::1]:8080" -> "::1". */
    static String hostWithoutPort(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String h = hostHeader.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end).toLowerCase(Locale.ROOT) : null;
        }
        int colon = h.indexOf(':');
        return (colon >= 0 ? h.substring(0, colon) : h).toLowerCase(Locale.ROOT);
    }

    private static String firstValue(String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return comma >= 0 ? header.substring(0, comma) : header;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
