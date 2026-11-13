package edu.eci.arsw.eciracego.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Cabeceras de seguridad en todas las respuestas (también en local, sin Caddy delante).
 * La CSP solo permite scripts propios y el SDK de Google Identity Services: un XSS que lograra
 * inyectar HTML no puede ejecutar JavaScript en línea ni cargar scripts de otros dominios.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", csp(request));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "geolocation=(self), camera=(), microphone=()");
        // El botón de Google abre una ventana emergente que debe poder avisar a esta página
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin-allow-popups");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    static String csp(HttpServletRequest request) {
        // WebSocket del mismo host (algunos navegadores no incluyen ws/wss en 'self')
        String host = request.getHeader("Host");
        String ws = (host != null && SAFE_HOST.matcher(host).matches()) ? " wss://" + host + " ws://" + host : "";
        return "default-src 'self'; "
                + "script-src 'self' https://accounts.google.com/gsi/client; "
                + "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; "
                + "img-src 'self' data: https://*.tile.openstreetmap.org https://*.googleusercontent.com; "
                + "font-src 'self'; "
                + "connect-src 'self'" + ws + " https://accounts.google.com/gsi/; "
                + "frame-src https://accounts.google.com/gsi/; "
                + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    }
}
