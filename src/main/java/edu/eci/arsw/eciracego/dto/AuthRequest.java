package edu.eci.arsw.eciracego.dto;

/**
 * Cuerpo de POST /api/auth/google y POST /api/auth/guest.
 * Para Google solo se aceptan el ID token (se verifica en el servidor) y el tag elegido;
 * email, nombre y foto salen del token verificado, nunca del cliente.
 */
public class AuthRequest {

    private String idToken;
    private String username;

    public AuthRequest() {
    }

    public AuthRequest(String idToken, String username) {
        this.idToken = idToken;
        this.username = username;
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
}
