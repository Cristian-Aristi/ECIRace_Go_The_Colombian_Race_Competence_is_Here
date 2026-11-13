package edu.eci.arsw.eciracego.dto;

/** Respuesta de inicio de sesión / cambio de tag: token de sesión + perfil del piloto. */
public record SessionResponse(String token, MyProfile profile) {
}
