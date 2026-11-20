package edu.eci.arsw.eciracego.model;

public enum RoomJoinResult {
    OK,
    ROOM_FULL,
    RACE_ALREADY_STARTED,
    ROOM_NOT_FOUND,
    INVALID_NAME,
    /** El host retiró a este piloto de la sala: no puede volver a entrar. */
    KICKED
}