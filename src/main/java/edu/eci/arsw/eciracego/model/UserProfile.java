package edu.eci.arsw.eciracego.model;

import java.io.Serializable;

/**
 * Representa el perfil del piloto y sus estadísticas dentro del ecosistema ECIRace GO.
 * Aplica el principio de Responsabilidad Única (SRP).
 */
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;
    private String email;
    private String fullName;
    private String pictureUrl;
    private int racesCount;
    private int winsCount;
    private double rating;

    public UserProfile() {
        this.racesCount = 0;
        this.winsCount = 0;
        this.rating = 0.0;
    }

    public UserProfile(String username, String email, String fullName, String pictureUrl) {
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.pictureUrl = pictureUrl;
        this.racesCount = 0;
        this.winsCount = 0;
        this.rating = 0.0;
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
}
