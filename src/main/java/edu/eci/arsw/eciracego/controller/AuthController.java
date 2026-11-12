package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para operaciones de autenticación y consulta de perfil.
 * Cumple con SRP (Single Responsibility) y DIP (inyección de AuthService).
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Endpoint para autenticación de Google Identity Services.
     */
    @PostMapping("/google")
    public ResponseEntity<UserProfile> loginWithGoogle(@RequestBody AuthRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        UserProfile profile = authService.authenticateGoogle(request);
        return ResponseEntity.ok(profile);
    }

    /**
     * Endpoint para autenticación rápida / modo de pruebas local sin OAuth.
     */
    @PostMapping("/guest")
    public ResponseEntity<UserProfile> loginGuest(@RequestBody(required = false) AuthRequest request) {
        String username = (request != null) ? request.getUsername() : null;
        UserProfile profile = authService.authenticateGuest(username);
        return ResponseEntity.ok(profile);
    }

    /**
     * Consulta el perfil y estadísticas del corredor por username.
     */
    @GetMapping("/profile/{username}")
    public ResponseEntity<UserProfile> getProfile(@PathVariable String username) {
        return authService.getProfile(username)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Actualiza el tag o nombre del piloto.
     */
    @PutMapping("/username")
    public ResponseEntity<UserProfile> updateUsername(@RequestBody java.util.Map<String, String> body) {
        String current = body.get("currentUsername");
        String next = body.get("newUsername");
        if (current == null || next == null || next.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        UserProfile updated = authService.updateUsername(current, next);
        return ResponseEntity.ok(updated);
    }

    /**
     * Tabla de clasificación: pilotos con más victorias (por defecto los 10 primeros).
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<java.util.List<UserProfile>> leaderboard(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(authService.getLeaderboard(limit));
    }

    /**
     * Registra una carrera finalizada para actualizar estadísticas en vivo.
     */
    @PostMapping("/race-result")
    public ResponseEntity<UserProfile> recordRace(@RequestBody java.util.Map<String, Object> body) {
        String username = (String) body.get("username");
        boolean won = Boolean.TRUE.equals(body.get("won"));
        UserProfile updated = authService.recordRace(username, won);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }
}
