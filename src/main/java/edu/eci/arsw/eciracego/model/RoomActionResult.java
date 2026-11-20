package edu.eci.arsw.eciracego.model;

public enum RoomActionResult {
    SUCCESS, NOT_HOST, ALREADY_STARTED, NOT_FOUND,
    /** Expulsar: el piloto indicado no está en la sala (o es el propio host). */
    RUNNER_NOT_FOUND
}