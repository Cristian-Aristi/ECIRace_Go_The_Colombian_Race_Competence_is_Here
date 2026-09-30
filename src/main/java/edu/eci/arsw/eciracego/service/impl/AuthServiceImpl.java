package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.AuthService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserProfileRepository userProfileRepository;

    @Autowired
    public AuthServiceImpl(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @PostConstruct
    public void initSeedData() {
        if (!userProfileRepository.existsById("Daniel")) {
            userProfileRepository.save(new UserProfile("Daniel", "daniel@escuelaing.edu.co", "Daniel", "", 125, 48, 5.0));
        }
        if (!userProfileRepository.existsById("Cristian")) {
            userProfileRepository.save(new UserProfile("Cristian", "cristian@escuelaing.edu.co", "Cristian", "", 125, 48, 5.0));
        }
        if (!userProfileRepository.existsById("Santiago")) {
            userProfileRepository.save(new UserProfile("Santiago", "santiago@escuelaing.edu.co", "Santiago", "", 125, 48, 5.0));
        }
    }

    @Override
    public UserProfile authenticateGoogle(AuthRequest request) {
        String effectiveUsername = resolveUsername(request.getUsername(), request.getFullName(), request.getEmail());
        log.info("[AuthService] Authenticating Google user: {} ({})", effectiveUsername, request.getEmail());

        return userProfileRepository.findById(effectiveUsername).map(existing -> {
            existing.setEmail(request.getEmail());
            existing.setFullName(request.getFullName());
            if (request.getPictureUrl() != null && !request.getPictureUrl().isBlank()) {
                existing.setPictureUrl(request.getPictureUrl());
            }
            return userProfileRepository.save(existing);
        }).orElseGet(() -> userProfileRepository.save(new UserProfile(
                effectiveUsername,
                request.getEmail(),
                request.getFullName(),
                request.getPictureUrl(),
                0,
                0,
                0.0
        )));
    }

    @Override
    public UserProfile authenticateGuest(String username) {
        String effectiveUsername = (username != null && !username.trim().isEmpty())
                ? username.trim()
                : "Piloto_" + (100 + (int) (Math.random() * 900));

        log.info("[AuthService] Authenticating guest racer: {}", effectiveUsername);

        return userProfileRepository.findById(effectiveUsername).orElseGet(() ->
                userProfileRepository.save(new UserProfile(
                        effectiveUsername,
                        effectiveUsername.toLowerCase() + "@ecirace.local",
                        effectiveUsername,
                        "",
                        0,
                        0,
                        0.0
                ))
        );
    }

    @Override
    public Optional<UserProfile> getProfile(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }
        return userProfileRepository.findById(username.trim());
    }

    @Override
    public boolean exists(String username) {
        return username != null && userProfileRepository.existsById(username.trim());
    }

    @Override
    public UserProfile updateUsername(String currentUsername, String newUsername) {
        if (currentUsername == null || newUsername == null || newUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid username");
        }
        String sanitizedNew = newUsername.trim();
        Optional<UserProfile> existingOpt = userProfileRepository.findById(currentUsername.trim());
        if (existingOpt.isPresent()) {
            UserProfile profile = existingOpt.get();
            userProfileRepository.delete(profile);
            profile.setUsername(sanitizedNew);
            return userProfileRepository.save(profile);
        } else {
            return userProfileRepository.save(new UserProfile(sanitizedNew, sanitizedNew.toLowerCase() + "@ecirace.local", sanitizedNew, ""));
        }
    }

    @Override
    public UserProfile recordRace(String username, boolean won) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        return userProfileRepository.findById(username.trim()).map(profile -> {
            profile.setRacesCount(profile.getRacesCount() + 1);
            if (won) {
                profile.setWinsCount(profile.getWinsCount() + 1);
            }
            double rate = (profile.getRacesCount() > 0) ? ((double) profile.getWinsCount() / profile.getRacesCount()) * 5.0 : 0.0;
            profile.setRating(Math.round(rate * 10.0) / 10.0);
            return userProfileRepository.save(profile);
        }).orElse(null);
    }

    private String resolveUsername(String providedUsername, String fullName, String email) {
        if (providedUsername != null && !providedUsername.trim().isEmpty()) {
            return providedUsername.trim();
        }
        if (fullName != null && !fullName.trim().isEmpty()) {
            return fullName.trim().split("\\s+")[0];
        }
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@')).trim();
        }
        return "Racer_" + (100 + (int) (Math.random() * 900));
    }
}
