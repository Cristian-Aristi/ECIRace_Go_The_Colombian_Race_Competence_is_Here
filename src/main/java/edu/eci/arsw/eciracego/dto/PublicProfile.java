package edu.eci.arsw.eciracego.dto;

import edu.eci.arsw.eciracego.model.UserProfile;

/** Perfil visible para cualquiera (perfil público y clasificación): sin correo ni datos de cuenta. */
public record PublicProfile(String username, int racesCount, int winsCount, double rating) {

    public static PublicProfile of(UserProfile p) {
        return new PublicProfile(p.getUsername(), p.getRacesCount(), p.getWinsCount(), p.getRating());
    }
}
