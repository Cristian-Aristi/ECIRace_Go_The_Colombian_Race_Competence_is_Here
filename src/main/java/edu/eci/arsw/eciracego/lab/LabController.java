package edu.eci.arsw.eciracego.lab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * POST /admin/config — el mismo contrato que do_POST de app/server.py del laboratorio.
 * Lo usa scripts/lab.py (fault, reset, improve, real).
 *
 * Cabecera:  X-Lab-Token: <ECIRACE_LAB_TOKEN>  (sin valor por defecto: scripts/lab.py genera uno aleatorio en .env)
 * Cuerpo:    {"ready": true|false, "delay_ms": 0..1000, "capacity": 0..1000}   (todos opcionales)
 *
 * 403 si el token no coincide, 400 si el cuerpo es inválido, 200 con el estado resultante.
 */
@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "ecirace.lab.enabled", havingValue = "true")
public class LabController {

    private static final Set<String> KEYS = Set.of("ready", "delay_ms", "capacity");

    private final LabState state;
    private final String token;

    public LabController(LabState state, @Value("${ecirace.lab.token:}") String token) {
        this.state = state;
        this.token = token == null ? "" : token.trim();
        if (this.token.length() < 16) {
            org.slf4j.LoggerFactory.getLogger(LabController.class)
                    .warn("ECIRACE_LAB_TOKEN vacío o corto (<16): /admin/config rechazará todas las solicitudes.");
        }
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> config(
            @RequestHeader(value = "X-Lab-Token", required = false) String header,
            @RequestBody(required = false) Map<String, Object> body) {

        // Comparación en tiempo constante; sin token configurado nadie puede cambiar el estado
        if (token.length() < 16 || header == null || !java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of());
        }
        if (body == null || !KEYS.containsAll(body.keySet())) {
            return invalid();
        }

        Object ready = body.get("ready");
        Object delay = body.get("delay_ms");
        Object capacity = body.get("capacity");

        if (ready != null && !(ready instanceof Boolean)) {
            return invalid();
        }
        if (delay != null && !(delay instanceof Number)) {
            return invalid();
        }
        if (capacity != null && !isWholeNumber(capacity)) {
            return invalid();
        }

        try {
            state.update(
                    (Boolean) ready,
                    delay == null ? null : toMillis((Number) delay),
                    capacity == null ? null : ((Number) capacity).intValue());
        } catch (IllegalArgumentException e) {
            return invalid();
        }
        return ResponseEntity.ok(state.snapshot());
    }

    private static int toMillis(Number value) {
        double d = value.doubleValue();
        if (Double.isNaN(d) || d < 0 || d > LabState.MAX_DELAY_MS) {
            throw new IllegalArgumentException("delay_ms fuera de rango");
        }
        return (int) Math.round(d);
    }

    private static boolean isWholeNumber(Object value) {
        if (!(value instanceof Number n)) {
            return false;
        }
        double d = n.doubleValue();
        return d == Math.rint(d) && d >= 0 && d <= LabState.MAX_CAPACITY;
    }

    private static ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "config invalida"));
    }
}
