package edu.eci.arsw.eciracego.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /lab/ready — el mismo /ready de app/server.py del laboratorio: 200 si la API acepta tráfico,
 * 503 durante una falla inyectada con "python scripts/lab.py fault". Lo consulta la sonda cada 2 s.
 *
 * No pasa por LabFilter (no está bajo /api), así que no lo afectan los cupos ni la espera simulada,
 * igual que en el laboratorio.
 */
@RestController
@ConditionalOnProperty(name = "ecirace.lab.enabled", havingValue = "true")
public class LabReadyController {

    private final LabState state;

    public LabReadyController(LabState state) {
        this.state = state;
    }

    @GetMapping("/lab/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean ready = state.isReady();
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("ready", ready));
    }
}
