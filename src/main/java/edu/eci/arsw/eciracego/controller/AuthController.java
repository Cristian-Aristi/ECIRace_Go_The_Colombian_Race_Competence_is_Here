package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.dto.MyProfile;
import edu.eci.arsw.eciracego.dto.PublicProfile;
import edu.eci.arsw.eciracego.dto.SessionResponse;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.security.GoogleIdentity;
import edu.eci.arsw.eciracego.security.GoogleTokenVerifier;
import edu.eci.arsw.eciracego.security.TokenService;
import edu.eci.arsw.eciracego.service.AuthService;
import edu.eci.arsw.eciracego.service.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Autenticación y perfiles de piloto.
 * <ul>
 *   <li>Google: el ID token se verifica en el servidor ({@link GoogleTokenVerifier}).</li>
 *   <li>Invitado: crea un piloto NUEVO; un tag existente no se puede reclamar.</li>
 *   <li>Ambos devuelven un token de sesión que el front envía como "Authorization: Bearer".</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final GoogleTokenVerifier googleVerifier;
    private final RoomService roomService;
    private final boolean demoTools;

    public AuthController(AuthService authService, TokenService tokenService,
                          GoogleTokenVerifier googleVerifier, RoomService roomService,
                          @org.springframework.beans.factory.annotation.Value("${ecirace.demo-tools:false}") boolean demoTools) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.googleVerifier = googleVerifier;
        this.roomService = roomService;
        this.demoTools = demoTools;
    }

    /**
     * Configuración pública del front: el client ID de Google (no es secreto) y si se muestran las
     * herramientas de demostración del corredor (simulador de ruta / "simular victoria").
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("googleEnabled", googleVerifier.isEnabled(),
                "googleClientId", googleVerifier.getClientId(),
                "demoTools", demoTools);
    }

    @PostMapping("/google")
    public SessionResponse loginWithGoogle(@RequestBody(required = false) AuthRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Falta el token de Google.");
        }
        GoogleIdentity identity = googleVerifier.verify(request.getIdToken());
        return session(authService.loginWithGoogle(identity, request.getUsername()));
    }

    @PostMapping("/guest")
    public ResponseEntity<SessionResponse> loginGuest(@RequestBody(required = false) AuthRequest request) {
        String username = (request != null) ? request.getUsername() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(session(authService.registerGuest(username)));
    }

    /** Perfil completo del piloto autenticado (incluye su correo). */
    @GetMapping("/me")
    public MyProfile me(HttpServletRequest http) {
        String user = AuthenticatedUser.require(http);
        return authService.getProfile(user).map(MyProfile::of).orElseThrow(ApiException::unauthorized);
    }

    /** Cierra la sesión en TODOS los dispositivos del piloto. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        authService.logout(AuthenticatedUser.require(http));
        return ResponseEntity.noContent().build();
    }

    /** Perfil público (sin correo) de cualquier piloto. */
    @GetMapping("/profile/{username}")
    public ResponseEntity<PublicProfile> getProfile(@PathVariable String username) {
        return authService.getProfile(username)
                .map(PublicProfile::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Cambia el tag del piloto AUTENTICADO. Devuelve un token nuevo (los anteriores dejan de servir). */
    @PutMapping("/username")
    public SessionResponse updateUsername(@RequestBody(required = false) Map<String, String> body, HttpServletRequest http) {
        String current = AuthenticatedUser.require(http);
        String next = body != null ? body.get("newUsername") : null;
        if (roomService.isInActiveRoom(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "IN_ROOM", "Sal de la sala de espera antes de cambiar tu tag.");
        }
        return session(authService.updateUsername(current, next));
    }

    /** Clasificación pública: pilotos con más victorias (sin correos). */
    @GetMapping("/leaderboard")
    public List<PublicProfile> leaderboard(@RequestParam(defaultValue = "10") int limit) {
        return authService.getLeaderboard(limit).stream().map(PublicProfile::of).toList();
    }

    private SessionResponse session(UserProfile profile) {
        return new SessionResponse(tokenService.issue(profile), MyProfile.of(profile));
    }
}
