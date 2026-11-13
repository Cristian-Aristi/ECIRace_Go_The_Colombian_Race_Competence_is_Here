package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.GoogleIdentity;
import edu.eci.arsw.eciracego.security.InputValidator;
import edu.eci.arsw.eciracego.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserProfileRepository userProfileRepository;

    @Autowired
    public AuthServiceImpl(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    @Transactional
    public UserProfile loginWithGoogle(GoogleIdentity identity, String requestedUsername) {
        Optional<UserProfile> linked = userProfileRepository.findByGoogleSubject(identity.subject());
        if (linked.isPresent()) {
            UserProfile profile = linked.get();
            // Datos frescos del token verificado de Google
            profile.setEmail(identity.email());
            profile.setFullName(identity.name());
            profile.setPictureUrl(safePicture(identity.pictureUrl()));
            if (profile.getSessionKey() == null) {
                profile.rotateSessionKey();
            }
            log.info("[AuthService] Inicio de sesión con Google: {}", profile.getUsername());
            return userProfileRepository.save(profile);
        }

        if (requestedUsername == null || requestedUsername.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_REQUIRED", "Elige tu tag de piloto para terminar el registro.");
        }
        String username = InputValidator.requireUsername(requestedUsername);
        ensureAvailable(username);

        UserProfile profile = new UserProfile(username, identity.email(), identity.name(), safePicture(identity.pictureUrl()));
        profile.setGoogleSubject(identity.subject());
        profile.setGuest(false);
        profile.rotateSessionKey();
        log.info("[AuthService] Nuevo piloto con Google: {}", username);
        return userProfileRepository.save(profile);
    }

    @Override
    @Transactional
    public UserProfile registerGuest(String requestedUsername) {
        String username;
        if (requestedUsername == null || requestedUsername.isBlank()) {
            username = randomGuestName();
        } else {
            username = InputValidator.requireUsername(requestedUsername);
            ensureAvailable(username);
        }
        UserProfile profile = new UserProfile(username, null, username, "");
        profile.setGuest(true);
        profile.rotateSessionKey();
        log.info("[AuthService] Nuevo piloto invitado: {}", username);
        return userProfileRepository.save(profile);
    }

    @Override
    public Optional<UserProfile> getProfile(String username) {
        if (username == null || username.trim().isEmpty() || username.length() > 64) {
            return Optional.empty();
        }
        return userProfileRepository.findById(username.trim());
    }

    @Override
    public boolean exists(String username) {
        return username != null && userProfileRepository.existsByUsernameIgnoreCase(username.trim());
    }

    @Override
    @Transactional
    public UserProfile updateUsername(String currentUsername, String newUsername) {
        String next = InputValidator.requireUsername(newUsername);
        UserProfile profile = userProfileRepository.findById(currentUsername)
                .orElseThrow(ApiException::unauthorized);
        if (next.equals(profile.getUsername())) {
            return profile;
        }
        // Solo cambia mayúsculas/minúsculas de su propio tag: no choca consigo mismo
        if (!next.equalsIgnoreCase(profile.getUsername())) {
            ensureAvailable(next);
        }
        userProfileRepository.delete(profile);
        userProfileRepository.flush();
        UserProfile renamed = new UserProfile(next, profile.getEmail(), profile.getFullName(), profile.getPictureUrl(),
                profile.getRacesCount(), profile.getWinsCount(), profile.getRating());
        renamed.setGoogleSubject(profile.getGoogleSubject());
        renamed.setGuest(profile.isGuest());
        renamed.rotateSessionKey();
        log.info("[AuthService] {} cambió su tag a {}", profile.getUsername(), next);
        return userProfileRepository.save(renamed);
    }

    @Override
    @Transactional
    public void logout(String username) {
        userProfileRepository.findById(username).ifPresent(p -> {
            p.rotateSessionKey();
            userProfileRepository.save(p);
        });
    }

    @Override
    public List<UserProfile> getLeaderboard(int limit) {
        int max = Math.max(1, Math.min(limit, 50));
        List<UserProfile> top = userProfileRepository.findTop50ByOrderByWinsCountDescRacesCountDescUsernameAsc();
        return top.size() > max ? top.subList(0, max) : top;
    }

    private void ensureAvailable(String username) {
        if (userProfileRepository.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Ese tag ya está registrado. Elige otro.");
        }
    }

    private String randomGuestName() {
        for (int i = 0; i < 20; i++) {
            String candidate = "Piloto_" + (1000 + RANDOM.nextInt(9000));
            if (!userProfileRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Intenta de nuevo con un tag propio.");
    }

    /** Solo se guardan fotos servidas por Google (https://*.googleusercontent.com). */
    private static String safePicture(String url) {
        if (url == null || url.length() > 512) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.endsWith(".googleusercontent.com") ? url : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
