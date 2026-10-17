package edu.eci.arsw.eciracego.dto;

import java.io.Serializable;

/**
 * Data Transfer Object (DTO) para la autenticación de usuarios.
 * Soporta autenticación de Google Identity Services y acceso de prueba (invitado).
 */
public class AuthRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String idToken;
    private String username;
    private String email;
    private String fullName;
    private String pictureUrl;

    public AuthRequest() {
    }

    public AuthRequest(String idToken, String username, String email, String fullName, String pictureUrl) {
        this.idToken = idToken;
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.pictureUrl = pictureUrl;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
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
}
