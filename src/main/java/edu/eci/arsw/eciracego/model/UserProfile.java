package edu.eci.arsw.eciracego.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.HexFormat;

@Entity
@Table(name = "user_profiles")
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    private String username;
    private String email;
    private String fullName;
    private String pictureUrl;
    private int racesCount;
    private int winsCount;
    private double rating;

    /** "sub" de la cuenta de Google vinculada (null en pilotos invitados). Nunca se expone. */
    @JsonIgnore
    @Column(unique = true)
    private String googleSubject;

    /**
     * Clave que firma cada token de sesión. Rotarla (cerrar sesión, cambiar el tag) invalida todos los
     * tokens emitidos antes. Los perfiles antiguos sin clave no pueden usarse hasta que alguien
     * inicie sesión de forma legítima con ellos (Google), así que no se pueden suplantar.
     */
    @JsonIgnore
    @Column(length = 64)
    private String sessionKey;

    /** Boolean (no boolean) para que la columna nueva admita NULL en bases de datos ya existentes. */
    private Boolean guest;

    public UserProfile() {
        this.racesCount = 0;
        this.winsCount = 0;
        this.rating = 0.0;
    }

    public UserProfile(String username, String email, String fullName, String pictureUrl) {
        this(username, email, fullName, pictureUrl, 0, 0, 0.0);
    }

    public UserProfile(String username, String email, String fullName, String pictureUrl, int racesCount, int winsCount, double rating) {
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.pictureUrl = pictureUrl;
        this.racesCount = racesCount;
        this.winsCount = winsCount;
        this.rating = rating;
    }

    /** Genera una clave de sesión nueva (invalida los tokens anteriores). */
    public void rotateSessionKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        this.sessionKey = HexFormat.of().formatHex(bytes);
    }

    /** Suma una carrera (y una victoria si corresponde) y recalcula el rating de 0 a 5. */
    public void registerRace(boolean won) {
        racesCount++;
        if (won) {
            winsCount++;
        }
        double rate = racesCount > 0 ? ((double) winsCount / racesCount) * 5.0 : 0.0;
        rating = Math.round(rate * 10.0) / 10.0;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public void setPictureUrl(String pictureUrl) {
        this.pictureUrl = pictureUrl;
    }

    public int getRacesCount() {
        return racesCount;
    }

    public void setRacesCount(int racesCount) {
        this.racesCount = racesCount;
    }

    public int getWinsCount() {
        return winsCount;
    }

    public void setWinsCount(int winsCount) {
        this.winsCount = winsCount;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public void setGoogleSubject(String googleSubject) {
        this.googleSubject = googleSubject;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public boolean isGuest() {
        return Boolean.TRUE.equals(guest);
    }

    public void setGuest(boolean guest) {
        this.guest = guest;
    }
}
