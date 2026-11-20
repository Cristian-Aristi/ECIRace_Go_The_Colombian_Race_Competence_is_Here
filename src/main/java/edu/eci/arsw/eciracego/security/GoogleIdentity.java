package edu.eci.arsw.eciracego.security;

/** Datos de un ID token de Google ya verificado (firma, emisor, audiencia y expiración). */
public record GoogleIdentity(String subject, String email, String name, String givenName, String pictureUrl) {
}
