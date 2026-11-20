package edu.eci.arsw.eciracego.config;

import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Las versiones anteriores sembraban en la base de datos los pilotos de prueba Daniel, Cristian y
 * Santiago (125 carreras, 48 victorias) y cualquiera podía entrar como ellos. Al arrancar se borran,
 * pero SOLO si siguen exactamente con los datos de la semilla y sin cuenta de Google vinculada.
 */
@Component
public class LegacyDataCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacyDataCleanup.class);
    private static final Map<String, String> SEEDS = Map.of(
            "Daniel", "daniel@escuelaing.edu.co",
            "Cristian", "cristian@escuelaing.edu.co",
            "Santiago", "santiago@escuelaing.edu.co");

    private final UserProfileRepository repository;

    public LegacyDataCleanup(UserProfileRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void removeSeedProfiles() {
        SEEDS.forEach((username, email) -> repository.findById(username)
                .filter(p -> email.equals(p.getEmail()) && p.getRacesCount() == 125 && p.getWinsCount() == 48
                        && p.getGoogleSubject() == null)
                .ifPresent(p -> {
                    repository.delete(p);
                    log.info("Perfil semilla de demostración eliminado: {}", username);
                }));
    }
}
