package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.service.AuthService;
import edu.eci.arsw.eciracego.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl();
    }

    @Test
    @DisplayName("Debe autenticar nuevo usuario con Google iniciando con 0 carreras reales")
    void testAuthenticateNewGoogleUser() {
        AuthRequest request = new AuthRequest(
                "dummy-google-jwt-token",
                "PilotoNuevo",
                "nuevo@mail.escuelaing.edu.co",
                "Piloto Nuevo",
                "https://lh3.googleusercontent.com/avatar.jpg"
        );

        UserProfile profile = authService.authenticateGoogle(request);

        assertNotNull(profile);
        assertEquals("PilotoNuevo", profile.getUsername());
        assertEquals("nuevo@mail.escuelaing.edu.co", profile.getEmail());
        assertEquals(0, profile.getRacesCount());
        assertEquals(0, profile.getWinsCount());
        assertTrue(authService.exists("PilotoNuevo"));
    }

    @Test
    @DisplayName("Debe permitir inicio de sesión en modo invitado con perfil semilla de demostración")
    void testAuthenticateSeedGuest() {
        UserProfile profile = authService.authenticateGuest("Daniel");

        assertNotNull(profile);
        assertEquals("Daniel", profile.getUsername());
        assertEquals(125, profile.getRacesCount());
        assertEquals(48, profile.getWinsCount());
    }

    @Test
    @DisplayName("Debe iniciar con 0 carreras a cualquier nuevo invitado desconocido")
    void testAuthenticateFreshGuest() {
        UserProfile profile = authService.authenticateGuest("Invitado_123");

        assertNotNull(profile);
        assertEquals("Invitado_123", profile.getUsername());
        assertEquals(0, profile.getRacesCount());
        assertEquals(0, profile.getWinsCount());
    }

    @Test
    @DisplayName("Debe recuperar perfil previamente autenticado")
    void testGetProfile() {
        authService.authenticateGuest("Santiago");

        Optional<UserProfile> profileOpt = authService.getProfile("Santiago");

        assertTrue(profileOpt.isPresent());
        assertEquals("Santiago", profileOpt.get().getUsername());
    }

    @Test
    @DisplayName("Debe permitir cambiar el tag o username preservando estadísticas")
    void testUpdateUsername() {
        authService.authenticateGuest("Daniel");
        UserProfile updated = authService.updateUsername("Daniel", "Daniel_Speed");

        assertNotNull(updated);
        assertEquals("Daniel_Speed", updated.getUsername());
        assertEquals(125, updated.getRacesCount());
        assertEquals(48, updated.getWinsCount());
        assertTrue(authService.exists("Daniel_Speed"));
        assertFalse(authService.exists("Daniel"));
    }

    @Test
    @DisplayName("Debe registrar y acumular carreras y victorias dinámicamente")
    void testRecordRace() {
        authService.authenticateGuest("CorredorPrueba");
        UserProfile afterWin = authService.recordRace("CorredorPrueba", true);

        assertNotNull(afterWin);
        assertEquals(1, afterWin.getRacesCount());
        assertEquals(1, afterWin.getWinsCount());

        UserProfile afterLoss = authService.recordRace("CorredorPrueba", false);
        assertEquals(2, afterLoss.getRacesCount());
        assertEquals(1, afterLoss.getWinsCount());
    }
}
