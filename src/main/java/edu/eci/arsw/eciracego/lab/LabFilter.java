package edu.eci.arsw.eciracego.lab;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Aplica el modo laboratorio a la API REST (/api/**), igual que do_GET de app/server.py:
 *   1) si la API no está lista            -> 503
 *   2) si no hay cupo libre               -> 503
 *   3) espera simulada de la dependencia  -> luego atiende la solicitud real (consulta JPA/H2)
 *
 * No toca /actuator (las métricas siguen respondiendo durante la falla), ni los WebSockets,
 * ni los archivos estáticos del front.
 *
 * Corre dentro del filtro de observación de Spring Boot, así que los 503 y la espera quedan
 * registrados en http_server_requests_seconds (como lab_requests_total y el histograma del lab).
 */
@Component
@ConditionalOnProperty(name = "ecirace.lab.enabled", havingValue = "true")
public class LabFilter extends OncePerRequestFilter {

    private static final String UNAVAILABLE = "{\"error\":\"no disponible o capacidad agotada\"}";

    private final LabState state;

    public LabFilter(LabState state) {
        this.state = state;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!state.isReady() || !state.tryAcquire()) {
            reject(response);
            return;
        }
        try {
            int delay = state.getDelayMs();
            if (delay > 0) {
                try {
                    Thread.sleep(delay); // Espera simulada de un servicio externo, NO tiempo de ECIRace
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reject(response);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            state.release();
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAVAILABLE);
    }
}
