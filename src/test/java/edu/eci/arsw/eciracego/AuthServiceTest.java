package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.GoogleIdentity;
import edu.eci.arsw.eciracego.service.AuthService;
import edu.eci.arsw.eciracego.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class AuthServiceTest {

    /** Repositorio en memoria sobre un mock (suficiente para la lógica del servicio). */
    private final Map<String, UserProfile> db = new HashMap<>();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        UserProfileRepository repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.save(any(UserProfile.class))).thenAnswer(inv -> {
            UserProfile p = inv.getArgument(0);
            db.put(p.getUsername(), p);
            return p;
        });
        Mockito.when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        Mockito.when(repo.existsByUsernameIgnoreCase(anyString())).thenAnswer(inv ->
                db.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(inv.getArgument(0))));
        Mockito.when(repo.findByGoogleSubject(anyString())).thenAnswer(inv -> db.values().stream()
                .filter(p -> inv.getArgument(0).equals(p.getGoogleSubject())).findFirst());
        Mockito.doAnswer(inv -> db.remove(((UserProfile) inv.getArgument(0)).getUsername())).when(repo).delete(any());
        Mockito.when(repo.findTop50ByOrderByWinsCountDescRacesCountDescUsernameAsc()).thenAnswer(inv ->
                db.values().stream().sorted((a, b) -> b.getWinsCount() - a.getWinsCount()).toList());

        authService = new AuthServiceImpl(repo);
    }

    private static GoogleIdentity google(String sub) {
        return new GoogleIdentity(sub, sub + "@gmail.com", "Nombre " + sub, "Nombre", "https://lh3.googleusercontent.com/a/x");
    }

    @Test
    @DisplayName("Un invitado nuevo inicia con 0 carreras y recibe clave de sesión")
    void freshGuest() {
        UserProfile profile = authService.registerGuest("Invitado_123");
        assertEquals("Invitado_123", profile.getUsername());
        assertEquals(0, profile.getRacesCount());
        assertTrue(profile.isGuest());
        assertNotNull(profile.getSessionKey());
    }

    @Test
    @DisplayName("Un tag existente NO se puede reclamar en modo invitado (antes devolvía el perfil ajeno)")
    void guestCannotClaimExistingName() {
        authService.registerGuest("Daniel");
        ApiException e = assertThrows(ApiException.class, () -> authService.registerGuest("daniel"));
        assertEquals("USERNAME_TAKEN", e.getCode());
    }

    @Test
    @DisplayName("Los tags inválidos se rechazan (HTML, espacios, demasiado largos)")
    void invalidNames() {
        assertThrows(ApiException.class, () -> authService.registerGuest("<img src=x onerror=alert(1)>"));
        assertThrows(ApiException.class, () -> authService.registerGuest("con espacio"));
        assertThrows(ApiException.class, () -> authService.registerGuest("x".repeat(17)));
    }

    @Test
    @DisplayName("Sin tag se genera un invitado aleatorio")
    void randomGuest() {
        assertTrue(authService.registerGuest(null).getUsername().startsWith("Piloto_"));
    }

    @Test
    @DisplayName("Google: la primera vez exige elegir tag; después entra directo con la misma cuenta")
    void googleFlow() {
        ApiException e = assertThrows(ApiException.class, () -> authService.loginWithGoogle(google("sub-1"), null));
        assertEquals("USERNAME_REQUIRED", e.getCode());

        UserProfile created = authService.loginWithGoogle(google("sub-1"), "PilotoNuevo");
        assertEquals("PilotoNuevo", created.getUsername());
        assertEquals("sub-1@gmail.com", created.getEmail());
        assertFalse(created.isGuest());

        UserProfile again = authService.loginWithGoogle(google("sub-1"), "OtroTag");
        assertEquals("PilotoNuevo", again.getUsername(), "La cuenta de Google ya vinculada ignora el tag pedido");
    }

    @Test
    @DisplayName("Google no puede apropiarse de un tag que ya es de otro piloto")
    void googleCannotTakeExistingName() {
        authService.registerGuest("Santiago");
        ApiException e = assertThrows(ApiException.class, () -> authService.loginWithGoogle(google("sub-2"), "Santiago"));
        assertEquals("USERNAME_TAKEN", e.getCode());
    }

    @Test
    @DisplayName("Solo se guardan fotos de googleusercontent.com")
    void pictureIsSanitized() {
        GoogleIdentity evil = new GoogleIdentity("sub-3", "a@gmail.com", "A", "A", "javascript:alert(1)");
        assertEquals("", authService.loginWithGoogle(evil, "Foto_Mala").getPictureUrl());
    }

    @Test
    @DisplayName("Cambiar el tag conserva estadísticas, rota la clave de sesión y no pisa a otro piloto")
    void rename() {
        UserProfile daniel = authService.registerGuest("Daniel");
        daniel.setRacesCount(10);
        daniel.setWinsCount(4);
        String oldKey = daniel.getSessionKey();
        authService.registerGuest("Ocupado");

        assertThrows(ApiException.class, () -> authService.updateUsername("Daniel", "ocupado"));

        UserProfile updated = authService.updateUsername("Daniel", "Daniel_Speed");
        assertEquals("Daniel_Speed", updated.getUsername());
        assertEquals(10, updated.getRacesCount());
        assertEquals(4, updated.getWinsCount());
        assertNotEquals(oldKey, updated.getSessionKey());
        assertTrue(authService.exists("Daniel_Speed"));
        assertFalse(authService.exists("Daniel"));
        assertTrue(authService.getProfile("Ocupado").isPresent(), "El otro perfil sigue intacto");
    }

    @Test
    @DisplayName("Cerrar sesión rota la clave (invalida los tokens anteriores)")
    void logoutRotatesKey() {
        UserProfile p = authService.registerGuest("Saliendo");
        String key = p.getSessionKey();
        authService.logout("Saliendo");
        assertNotEquals(key, authService.getProfile("Saliendo").orElseThrow().getSessionKey());
    }

    @Test
    @DisplayName("registerRace suma carreras/victorias y recalcula el rating")
    void registerRace() {
        UserProfile p = new UserProfile("X", null, "X", "");
        p.registerRace(true);
        p.registerRace(false);
        assertEquals(2, p.getRacesCount());
        assertEquals(1, p.getWinsCount());
        assertEquals(2.5, p.getRating());
    }

    @Test
    @DisplayName("La clasificación respeta el límite pedido")
    void leaderboardLimit() {
        for (int i = 0; i < 5; i++) {
            authService.registerGuest("Piloto" + i + "x");
        }
        List<UserProfile> top = authService.getLeaderboard(3);
        assertEquals(3, top.size());
    }
}
