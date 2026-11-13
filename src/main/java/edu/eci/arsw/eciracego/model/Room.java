package edu.eci.arsw.eciracego.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {

    public static final int MAX_PLAYERS = 6;
    /** Nombre que se muestra cuando la sala la crea el centro de control (PC) sin iniciar sesión. */
    public static final String CONTROL_CENTER_NAME = "Central de control";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum RoomStatus {
        WAITING,
        IN_PROGRESS,
        FINISHED
    }

    private String roomCode;
    /** Nombre visible del host (el tag del piloto, o "Central de control"). */
    private String hostName;
    /** Piloto autenticado que creó la sala (null si la creó el centro de control sin sesión). */
    private String hostUser;
    /**
     * Clave secreta del host: se entrega UNA vez al crear la sala y se exige para iniciar la carrera,
     * mover la meta o ver el tablero sin ser corredor. Nunca se serializa.
     */
    @JsonIgnore
    private final String hostKey;
    private RoomStatus status;
    private long createdAt;
    private volatile long lastActivity;
    private volatile long finishedAt;
    private final Set<String> activeRunners = ConcurrentHashMap.newKeySet();
    private String winnerId = null;

    private double metaLat = 4.782800;
    private double metaLon = -74.043000;
    private final double RADIO_META_METROS = 15.0;

    public Room() {
        this(null, null);
    }

    public Room(String roomCode, String hostUser) {
        this.roomCode = roomCode;
        this.hostUser = hostUser;
        this.hostName = hostUser != null ? hostUser : CONTROL_CENTER_NAME;
        this.hostKey = newHostKey();
        this.status = RoomStatus.WAITING;
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = this.createdAt;
    }

    private static String newHostKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    public String getHostUser() {
        return hostUser;
    }

    @JsonIgnore
    public String getHostKey() {
        return hostKey;
    }

    public double getMetaLat() { return metaLat; }

    public double getMetaLon() { return metaLon; }

    public double getRadioMetaMetros() { return RADIO_META_METROS; }

    public synchronized String getWinnerId() { return winnerId; }

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
        touch();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @JsonIgnore
    public long getLastActivity() {
        return lastActivity;
    }

    @JsonIgnore
    public long getFinishedAt() {
        return finishedAt;
    }

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
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

    /** Comparación EXACTA con el piloto autenticado que creó la sala (los tags son únicos sin importar mayúsculas). */
    public boolean isHost(String username) {
        return hostUser != null && hostUser.equals(username);
    }

    public boolean isMember(String username) {
        return username != null && activeRunners.contains(username);
    }

    /** Compara la clave del host en tiempo constante. */
    public boolean checkHostKey(String candidate) {
        return candidate != null && !candidate.isEmpty() && MessageDigest.isEqual(
                hostKey.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** Puede iniciar la carrera y mover la meta: el piloto host o quien tenga la clave del host. */
    public boolean canControl(String username, String hostKeyCandidate) {
        return isHost(username) || checkHostKey(hostKeyCandidate);
    }

    /** Puede ver el estado, el roster y las posiciones: corredores de la sala y el host. */
    public boolean canView(String username, String hostKeyCandidate) {
        return isMember(username) || canControl(username, hostKeyCandidate);
    }

    /**
     * Intenta registrar un corredor en la sala respetando el cupo máximo
     * (6 jugadores) y el estado de la carrera (solo se puede entrar
     * mientras está en WAITING).
     * <p>
     * Sincronizado a nivel de INSTANCIA: cada sala tiene su propio candado,
     * así que dos salas distintas pueden procesar joins al mismo tiempo
     * sin bloquearse entre sí.
     */
    public synchronized RoomJoinResult addRunner(String runnerId) {
        if (runnerId == null || runnerId.trim().isEmpty()) {
            return RoomJoinResult.INVALID_NAME;
        }
        String cleanId = runnerId.trim();
        if (activeRunners.contains(cleanId)) {
            return RoomJoinResult.OK; // reconexión idempotente del mismo piloto
        }
        if (status != RoomStatus.WAITING) {
            return RoomJoinResult.RACE_ALREADY_STARTED;
        }
        if (activeRunners.size() >= MAX_PLAYERS) {
            return RoomJoinResult.ROOM_FULL;
        }
        activeRunners.add(cleanId);
        touch();
        return RoomJoinResult.OK;
    }

    /** Solo se puede salir de la sala antes de la largada. */
    public synchronized boolean removeRunner(String runnerId) {
        if (runnerId == null || status != RoomStatus.WAITING) {
            return false;
        }
        boolean removed = activeRunners.remove(runnerId.trim());
        if (removed) {
            touch();
        }
        return removed;
    }

    /** Mueve la meta (solo antes de la largada). La autorización la valida quien llama. */
    public synchronized boolean moveMeta(double mLat, double mLon) {
        if (status != RoomStatus.WAITING) {
            return false;
        }
        this.metaLat = mLat;
        this.metaLon = mLon;
        touch();
        return true;
    }

    /**
     * Inicia la carrera si la sala sigue en WAITING. La autorización (host o clave del host) la valida
     * quien llama. Sincronizado para que dos clics casi simultáneos no disparen dos transiciones.
     */
    public synchronized boolean startRace(double mLat, double mLon) {
        if (status != RoomStatus.WAITING) {
            return false;
        }
        this.metaLat = mLat;
        this.metaLon = mLon;
        this.status = RoomStatus.IN_PROGRESS;
        touch();
        return true;
    }

    public synchronized boolean finishRace() {
        if (status != RoomStatus.IN_PROGRESS) {
            return false;
        }
        status = RoomStatus.FINISHED;
        finishedAt = System.currentTimeMillis();
        touch();
        return true;
    }

    public synchronized boolean declareWinner(String runnerId) {
        if (this.status == RoomStatus.IN_PROGRESS) {
            this.status = RoomStatus.FINISHED;
            this.winnerId = runnerId;
            this.finishedAt = System.currentTimeMillis();
            touch();
            return true;
        }
        return false;
    }
}
