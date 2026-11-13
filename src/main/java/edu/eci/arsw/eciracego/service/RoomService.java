package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface RoomService {

    /**
     * Crea una nueva sala de competición.
     * @param hostUser Piloto autenticado que la crea, o null si la crea el centro de control sin sesión.
     * @param preferredCode Código deseado (ya validado) o null para autogenerar.
     * @return La sala creada, o vacío si el código ya está en uso.
     * @throws IllegalStateException si se alcanzó el máximo de salas (ecirace.rooms.max).
     */
    Optional<Room> createRoom(String hostUser, String preferredCode);

    Optional<Room> getRoom(String roomCode);

    /** Todas las salas (solo para uso interno y métricas). */
    Collection<Room> getAllRooms();

    boolean roomExists(String roomCode);

    RoomJoinResult registerRunnerInRoom(String roomCode, String runnerId);

    /** Retira al corredor (solo antes de la largada). */
    boolean removeRunnerFromRoom(String roomCode, String runnerId);

    /** Inicia la carrera si quien lo pide es el piloto host o presenta la clave del host. */
    RoomActionResult startRace(String roomCode, String requester, String hostKey, double metaLat, double metaLon);

    /** Mueve la meta antes de la largada (mismas reglas de autorización que startRace). */
    RoomActionResult moveMeta(String roomCode, String requester, String hostKey, double metaLat, double metaLon);

    /** true si el usuario es corredor o host de la sala, o presenta la clave del host. */
    boolean canView(String roomCode, String requester, String hostKey);

    boolean isRaceInProgress(String roomCode);

    /** true si el piloto está en una sala que no ha terminado (no puede cambiar su tag mientras tanto). */
    boolean isInActiveRoom(String username);

    /** Elimina salas terminadas o abandonadas; devuelve los códigos eliminados. */
    List<String> cleanup(long now);
}
