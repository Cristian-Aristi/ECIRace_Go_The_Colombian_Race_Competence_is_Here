package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Room;
import java.util.Collection;
import java.util.Optional;

public interface RoomService {

    /**
     * Crea una nueva sala de competición.
     * @param hostName Nombre del host o dispositivo central.
     * @param preferredCode Código deseado o null/vacío para autogenerar.
     * @return La sala creada.
     */
    Room createRoom(String hostName, String preferredCode);

    /**
     * Obtiene una sala por su código alfanumérico.
     * @param roomCode Código de sala.
     * @return Optional con la sala encontrada.
     */
    Optional<Room> getRoom(String roomCode);

    /**
     * Lista todas las salas activas.
     * @return Colección de salas.
     */
    Collection<Room> getAllRooms();

    /**
     * Verifica si una sala existe.
     * @param roomCode Código de la sala.
     * @return true si existe, false si no.
     */
    boolean roomExists(String roomCode);

    /**
     * Registra a un corredor dentro de la sala.
     * @param roomCode Código de la sala.
     * @param runnerId Identificador del corredor.
     */
    void registerRunnerInRoom(String roomCode, String runnerId);

    /**
     * Retira a un corredor de la sala.
     * @param roomCode Código de la sala.
     * @param runnerId Identificador del corredor.
     */
    void removeRunnerFromRoom(String roomCode, String runnerId);
}