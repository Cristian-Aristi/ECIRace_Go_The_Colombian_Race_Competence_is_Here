package edu.eci.arsw.eciracego.model;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {

    public static final int MAX_PLAYERS = 6;

    public enum RoomStatus {
        WAITING,
        IN_PROGRESS,
        FINISHED
    }

    private String roomCode;
    private String hostName;
    private RoomStatus status;
    private long createdAt;
    private final Set<String> activeRunners = ConcurrentHashMap.newKeySet();

    public Room() {
        this.createdAt = System.currentTimeMillis();
        this.status = RoomStatus.WAITING;
    }

    public Room(String roomCode, String hostName) {
        this.roomCode = roomCode;
        this.hostName = hostName;
        this.status = RoomStatus.WAITING;
        this.createdAt = System.currentTimeMillis();
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    /**
     * Sincronizado porque el estado de la sala se lee y se escribe desde
     * varios hilos a la vez (peticiones REST de start/join y el executor
     * de telemetría de RaceController).
     */
    public synchronized RoomStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(RoomStatus status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Set<String> getActiveRunners() {
        return Collections.unmodifiableSet(activeRunners);
    }

    public int getRunnersCount() {
        return activeRunners.size();
    }

    public boolean isFull() {
        return activeRunners.size() >= MAX_PLAYERS;
    }

    public boolean isHost(String candidateName) {
        return hostName != null && candidateName != null
                && hostName.trim().equalsIgnoreCase(candidateName.trim());
    }

    /**
     * Intenta registrar un corredor en la sala respetando el cupo máximo
     * (6 jugadores) y el estado de la carrera (solo se puede entrar
     * mientras está en WAITING).
     * <p>
     * Sincronizado a nivel de INSTANCIA: cada sala tiene su propio candado,
     * así que dos salas distintas pueden procesar joins al mismo tiempo
     * sin bloquearse entre sí (evita el cuello de botella global que
     * pediría el atributo de calidad de Escalabilidad).
     */
    public synchronized RoomJoinResult addRunner(String runnerId) {
        if (runnerId == null || runnerId.trim().isEmpty()) {
            return RoomJoinResult.INVALID_NAME;
        }
        if (status != RoomStatus.WAITING) {
            return RoomJoinResult.RACE_ALREADY_STARTED;
        }
        String cleanId = runnerId.trim();
        if (activeRunners.contains(cleanId)) {
            return RoomJoinResult.OK; // reconexión idempotente del mismo piloto
        }
        if (activeRunners.size() >= MAX_PLAYERS) {
            return RoomJoinResult.ROOM_FULL;
        }
        activeRunners.add(cleanId);
        return RoomJoinResult.OK;
    }

    public void removeRunner(String runnerId) {
        if (runnerId != null) {
            activeRunners.remove(runnerId.trim());
        }
    }

    /**
     * Solo el host puede iniciar la carrera, y solo si la sala sigue en
     * WAITING. Sincronizado para que dos clics casi simultáneos del host
     * (por ejemplo doble-tap en el botón) no disparen dos transiciones.
     */
    public synchronized boolean startRace(String requesterName) {
        if (!isHost(requesterName)) {
            return false;
        }
        if (status != RoomStatus.WAITING) {
            return false;
        }
        status = RoomStatus.IN_PROGRESS;
        return true;
    }

    public synchronized boolean finishRace() {
        if (status != RoomStatus.IN_PROGRESS) {
            return false;
        }
        status = RoomStatus.FINISHED;
        return true;
    }
}