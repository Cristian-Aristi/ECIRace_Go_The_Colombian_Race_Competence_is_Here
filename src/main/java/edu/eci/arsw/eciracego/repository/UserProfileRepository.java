package edu.eci.arsw.eciracego.repository;

import edu.eci.arsw.eciracego.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

    /** Los tags son únicos sin importar mayúsculas: "daniel" no puede registrarse si existe "Daniel". */
    boolean existsByUsernameIgnoreCase(String username);

    Optional<UserProfile> findByGoogleSubject(String googleSubject);

    /** Clasificación calculada por la base de datos (antes se cargaban todos los perfiles en memoria). */
    List<UserProfile> findTop50ByOrderByWinsCountDescRacesCountDescUsernameAsc();
}
