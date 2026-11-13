package edu.eci.arsw.eciracego.dto;

import edu.eci.arsw.eciracego.model.Room;

/** Lo que se lista públicamente de una sala: sin roster, host ni coordenadas de la meta. */
public record RoomSummary(String roomCode, Room.RoomStatus status, int runnersCount, int maxPlayers) {

    public static RoomSummary of(Room r) {
        return new RoomSummary(r.getRoomCode(), r.getStatus(), r.getRunnersCount(), Room.MAX_PLAYERS);
    }
}
