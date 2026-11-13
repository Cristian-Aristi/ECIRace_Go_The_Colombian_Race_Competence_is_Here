package edu.eci.arsw.eciracego.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Límite de peticiones por IP en /api/**.
 * <ul>
 *   <li>Crear cuentas, iniciar sesión y crear salas: ráfaga de 10 y luego 1 cada 6 s.</li>
 *   <li>El resto de la API: ráfaga de 120 y 20 por segundo.</li>
 * </ul>
 * La IP es la del cliente real: detrás de Caddy, server.forward-headers-strategy=framework hace que
 * getRemoteAddr() devuelva X-Forwarded-For (Caddy lo reescribe, no se puede falsificar desde fuera).
 * Se puede apagar con ECIRACE_RATELIMIT_ENABLED=false (pruebas de carga locales con k6).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> SENSITIVE = Set.of("/api/auth/guest", "/api/auth/google", "/api/rooms/create");
    private static final String BODY = "{\"error\":\"Demasiadas solicitudes. Espera un momento e inténtalo de nuevo.\",\"code\":\"RATE_LIMITED\"}";

    private final RateLimiter limiter;
    private final boolean enabled;

    public RateLimitFilter(RateLimiter limiter, @Value("${ecirace.ratelimit.enabled:true}") boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        boolean sensitive = "POST".equalsIgnoreCase(request.getMethod()) && SENSITIVE.contains(request.getRequestURI());
        boolean allowed = sensitive
                ? limiter.tryAcquire("auth:" + ip, 10, 1.0 / 6.0)
                : limiter.tryAcquire("api:" + ip, 120, 20.0);
        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", sensitive ? "6" : "1");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
