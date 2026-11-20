package edu.eci.arsw.eciracego.dto;

import edu.eci.arsw.eciracego.model.UserProfile;

/** Perfil completo: solo se entrega al propio piloto autenticado. */
public record MyProfile(String username, String email, String fullName, String pictureUrl,
                        int racesCount, int winsCount, double rating, boolean google) {

    public static MyProfile of(UserProfile p) {
        return new MyProfile(p.getUsername(), p.getEmail(), p.getFullName(), p.getPictureUrl(),
                p.getRacesCount(), p.getWinsCount(), p.getRating(), p.getGoogleSubject() != null);
    }
}
