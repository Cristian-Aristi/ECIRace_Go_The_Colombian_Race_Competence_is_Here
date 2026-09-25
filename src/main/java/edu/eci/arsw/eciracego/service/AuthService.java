package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.model.UserProfile;

import java.util.Optional;

/**
 * Contrato de servicio de autenticación y gestión de perfiles de corredor.
 * Sigue los principios SOLID (Interface Segregation y Dependency Inversion).
 */
public interface AuthService {

    /**
     * Autentica o registra un usuario mediante Google Identity Services.
     * @param request Datos de autenticación y credenciales provenientes de Google.
     * @return Perfil consolidado del corredor con estadísticas.
     */
    UserProfile authenticateGoogle(AuthRequest request);

    /**
     * Autentica un piloto en modo invitado o pruebas locales (para desarrollo y calificación).
     * @param username Nombre del piloto.
     * @return Perfil del corredor inicializado.
     */
    UserProfile authenticateGuest(String username);

    /**
     * Obtiene el perfil y estadísticas de un corredor por su nombre de usuario.
     * @param username Nombre único del piloto.
     * @return Perfil si existe.
     */
    Optional<UserProfile> getProfile(String username);

    /**
     * Verifica si un nombre de usuario ya está registrado en la sesión activa.
     * @param username Nombre a verificar.
     * @return true si ya existe.
     */
    boolean exists(String username);

    /**
     * Actualiza el tag o nombre de usuario del piloto.
     */
    UserProfile updateUsername(String currentUsername, String newUsername);

    /**
     * Registra el resultado de una carrera disputada por el piloto.
     */
    UserProfile recordRace(String username, boolean won);
}
