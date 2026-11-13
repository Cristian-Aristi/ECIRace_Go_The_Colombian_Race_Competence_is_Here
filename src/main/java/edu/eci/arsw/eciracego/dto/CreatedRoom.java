package edu.eci.arsw.eciracego.dto;

import edu.eci.arsw.eciracego.model.Room;

/** Respuesta de POST /api/rooms/create: la sala y, una sola vez, la clave secreta del host. */
public record CreatedRoom(Room room, String hostKey) {
}
