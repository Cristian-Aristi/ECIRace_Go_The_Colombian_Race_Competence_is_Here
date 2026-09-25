package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación del servicio de autenticación con almacenamiento thread-safe.
 * Cumple con SRP (Single Responsibility) y OCP (Open/Closed).
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    // Almacenamiento thread-safe en memoria para perfiles de pilotos
    private final ConcurrentHashMap<String, UserProfile> userProfiles = new ConcurrentHashMap<>();

    public AuthServiceImpl() {
        // Datos semilla del mockup exclusivamente como ejemplos de demostración
        userProfiles.put("Daniel", new UserProfile("Daniel", "daniel@escuelaing.edu.co", "Daniel", "", 125, 48, 5.0));
        userProfiles.put("Cristian", new UserProfile("Cristian", "cristian@escuelaing.edu.co", "Cristian", "", 125, 48, 5.0));
        userProfiles.put("Santiago", new UserProfile("Santiago", "santiago@escuelaing.edu.co", "Santiago", "", 125, 48, 5.0));
    }

    @Override
    public UserProfile authenticateGoogle(AuthRequest request) {
        String effectiveUsername = resolveUsername(request.getUsername(), request.getFullName(), request.getEmail());
        
        logger.info("🔑 [AuthService] Autenticando usuario de Google: {} ({})", effectiveUsername, request.getEmail());

        return userProfiles.compute(effectiveUsername, (key, existing) -> {
            if (existing != null) {
                // Actualizar datos de Google preservando estadísticas de carrera
                existing.setEmail(request.getEmail());
                existing.setFullName(request.getFullName());
                if (request.getPictureUrl() != null && !request.getPictureUrl().isBlank()) {
                    existing.setPictureUrl(request.getPictureUrl());
                }
                return existing;
            } else {
                // Nuevo perfil real: arranca desde 0 carreras y 0 victorias (no quemadas)
                return new UserProfile(
                        key,
                        request.getEmail(),
                        request.getFullName(),
                        request.getPictureUrl(),
                        0,
                        0,
                        0.0
                );
            }
        });
    }

    @Override
    public UserProfile authenticateGuest(String username) {
        String effectiveUsername = (username != null && !username.trim().isEmpty())
                ? username.trim()
                : "Piloto_" + (100 + (int) (Math.random() * 900));

        logger.info("🎮 [AuthService] Autenticando piloto en modo local: {}", effectiveUsername);

        return userProfiles.computeIfAbsent(effectiveUsername, key -> new UserProfile(
                key,
                key.toLowerCase() + "@ecirace.local",
                key,
                "",
                0,
                0,
                0.0
        ));
    }

    @Override
    public Optional<UserProfile> getProfile(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userProfiles.get(username.trim()));
    }

    @Override
    public boolean exists(String username) {
        return username != null && userProfiles.containsKey(username.trim());
    }

    @Override
    public UserProfile updateUsername(String currentUsername, String newUsername) {
        if (currentUsername == null || newUsername == null || newUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Nombre de usuario no válido");
        }
        String sanitizedNew = newUsername.trim();
        UserProfile existing = userProfiles.remove(currentUsername.trim());
        if (existing != null) {
            existing.setUsername(sanitizedNew);
            userProfiles.put(sanitizedNew, existing);
            return existing;
        } else {
            UserProfile created = new UserProfile(sanitizedNew, sanitizedNew.toLowerCase() + "@ecirace.local", sanitizedNew, "");
            userProfiles.put(sanitizedNew, created);
            return created;
        }
    }

    @Override
    public UserProfile recordRace(String username, boolean won) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        return userProfiles.computeIfPresent(username.trim(), (k, profile) -> {
            profile.setRacesCount(profile.getRacesCount() + 1);
            if (won) {
                profile.setWinsCount(profile.getWinsCount() + 1);
            }
            double rate = (profile.getRacesCount() > 0) ? ((double) profile.getWinsCount() / profile.getRacesCount()) * 5.0 : 0.0;
            profile.setRating(Math.round(rate * 10.0) / 10.0);
            return profile;
        });
    }

    private String resolveUsername(String providedUsername, String fullName, String email) {
        if (providedUsername != null && !providedUsername.trim().isEmpty()) {
            return providedUsername.trim();
        }
        if (fullName != null && !fullName.trim().isEmpty()) {
            // Tomar el primer nombre o apodo (ej. "Daniel" de "Daniel Romero")
            return fullName.trim().split("\\s+")[0];
        }
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@')).trim();
        }
        return "Racer_" + (100 + (int) (Math.random() * 900));
    }
}
