package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.security.GoogleIdentity;

import java.util.List;
import java.util.Optional;

/**
 * Cuentas de piloto. Toda operación que modifica un perfil recibe el username del token de sesión
 * validado (nunca del cuerpo de la petición).
 */
public interface AuthService {

    /**
     * Inicia sesión con una identidad de Google YA verificada. Si la cuenta de Google aún no tiene
     * piloto, lo crea con el tag pedido (debe estar libre); si no se envía tag, lanza USERNAME_REQUIRED.
     */
    UserProfile loginWithGoogle(GoogleIdentity identity, String requestedUsername);

    /** Crea un piloto invitado nuevo. Un tag ya registrado NO se puede reclamar (devuelve 409). */
    UserProfile registerGuest(String requestedUsername);

    Optional<UserProfile> getProfile(String username);

    /** true si el tag ya existe (sin importar mayúsculas). */
    boolean exists(String username);

    /** Cambia el tag del piloto autenticado; rota su clave de sesión. */
    UserProfile updateUsername(String currentUsername, String newUsername);

    /** Cierra todas las sesiones del piloto (rota la clave de sesión). */
    void logout(String username);

    /** Pilotos con más victorias (desempata por carreras disputadas y luego por nombre). */
    List<UserProfile> getLeaderboard(int limit);
}
