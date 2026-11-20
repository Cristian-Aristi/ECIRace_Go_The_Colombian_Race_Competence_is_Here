package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.InputValidator;
import edu.eci.arsw.eciracego.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    /** Sin "0/O" ni "1/I" para que el código se pueda dictar sin confusiones. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    static final long FINISHED_TTL_MS = 15 * 60_000L;
    static final long WAITING_IDLE_TTL_MS = 2 * 60 * 60_000L;
    static final long IN_PROGRESS_TTL_MS = 60 * 60_000L;
    public static final int DEFAULT_MAX_ROOMS = 2000;
    public static final int DEFAULT_MAX_ROOMS_PER_CREATOR = 5;

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Máximo de salas simultáneas en memoria (antes no había tope y se podían crear salas en bucle). */
    private final int maxRooms;
    /** Máximo de salas sin terminar por creador (piloto o IP del centro de control). 0 = sin límite. */
    private final int maxRoomsPerCreator;
    /** Serializa los "join": un piloto no puede quedar en dos salas de espera por peticiones simultáneas. */
    private final Object joinLock = new Object();

    public RoomServiceImpl() {
        this(DEFAULT_MAX_ROOMS);
    }

    public RoomServiceImpl(int maxRooms) {
        this(maxRooms, DEFAULT_MAX_ROOMS_PER_CREATOR, true);
    }

    /**
     * @param rateLimitEnabled con ECIRACE_RATELIMIT_ENABLED=false (pruebas de carga con k6, donde cada
     *                         usuario virtual crea una sala por iteración) tampoco hay cupo por creador.
     */
    @Autowired
    public RoomServiceImpl(@Value("${ecirace.rooms.max:" + DEFAULT_MAX_ROOMS + "}") int maxRooms,
                           @Value("${ecirace.rooms.max-per-creator:" + DEFAULT_MAX_ROOMS_PER_CREATOR + "}") int maxRoomsPerCreator,
                           @Value("${ecirace.ratelimit.enabled:true}") boolean rateLimitEnabled) {
        this.maxRooms = Math.max(1, maxRooms);
        this.maxRoomsPerCreator = rateLimitEnabled ? Math.max(0, maxRoomsPerCreator) : 0;
    }

    @Override
    public synchronized Optional<Room> createRoom(String hostUser, String preferredCode, String creatorKey) {
        String roomCode = (preferredCode != null && !preferredCode.isBlank())
                ? InputValidator.requireRoomCode(preferredCode)
                : null;
        if (roomCode != null && rooms.containsKey(roomCode)) {
            log.warn("[RoomService] Código de sala {} ya está en uso", roomCode);
            return Optional.empty();
        }
        ensureCreatorQuota(creatorKey);
        if (rooms.size() >= maxRooms) {
            throw new IllegalStateException("Se alcanzó el máximo de salas activas");
        }
        if (roomCode == null) {
            roomCode = generateUniqueCode();
        }

        Room newRoom = new Room(roomCode, hostUser);
        newRoom.setCreatorKey(creatorKey);
        // putIfAbsent es ATÓMICO: dos hosts creando el mismo código a la vez no se pisan
        Room existing = rooms.putIfAbsent(roomCode, newRoom);
        if (existing != null) {
            log.warn("[RoomService] Código de sala {} ya está en uso", roomCode);
            return Optional.empty();
        }
        log.info("[RoomService] Sala creada: {} | Host: {}", roomCode, newRoom.getHostName());
        return Optional.of(newRoom);
    }

    /**
     * Cupo de salas sin terminar por creador. Si está lleno, primero se cierran sus salas de espera VACÍAS
     * más antiguas (el caso normal: recargar el tablero y crear otra). Si aun así no hay cupo, 429.
     */
    private void ensureCreatorQuota(String creatorKey) {
        if (creatorKey == null || maxRoomsPerCreator <= 0) {
            return;
        }
        List<Room> mine = rooms.values().stream()
                .filter(r -> creatorKey.equals(r.getCreatorKey()) && r.getStatus() != Room.RoomStatus.FINISHED)
                .sorted(Comparator.comparingLong(Room::getCreatedAt))
                .toList();
        int excess = mine.size() - maxRoomsPerCreator + 1;
        for (Room r : mine) {
            if (excess <= 0) {
                break;
            }
            if (r.getStatus() == Room.RoomStatus.WAITING && r.getRunnersCount() == 0 && rooms.remove(r.getRoomCode(), r)) {
                log.info("[RoomService] Sala vacía {} cerrada: su creador abrió otra", r.getRoomCode());
                excess--;
            }
        }
        if (excess > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ROOMS",
                    "Ya tienes " + maxRoomsPerCreator + " salas abiertas. Termina o abandona una antes de crear otra.");
        }
    }

    @Override
    public Optional<Room> getRoom(String roomCode) {
        String code = InputValidator.normalizeRoomCode(roomCode);
        return code == null ? Optional.empty() : Optional.ofNullable(rooms.get(code));
    }

    @Override
    public Collection<Room> getAllRooms() {
        return Collections.unmodifiableCollection(rooms.values());
    }

    @Override
    public boolean roomExists(String roomCode) {
        return getRoom(roomCode).isPresent();
    }

    @Override
    public RoomJoinResult registerRunnerInRoom(String roomCode, String runnerId, List<Room> leftRooms) {
        Optional<Room> room = getRoom(roomCode);
        if (room.isEmpty()) {
            return RoomJoinResult.ROOM_NOT_FOUND;
        }
        Room target = room.get();
        synchronized (joinLock) {
            RoomJoinResult result = target.addRunner(runnerId);
            log.info("[RoomService] {} intenta unirse a {} -> {}", runnerId, target.getRoomCode(), result);
            if (result == RoomJoinResult.OK) {
                // Una sola sala de espera por piloto: sale de las demás
                for (Room other : rooms.values()) {
                    if (other != target && other.getStatus() == Room.RoomStatus.WAITING
                            && other.isMember(runnerId) && other.removeRunner(runnerId)) {
                        log.info("[RoomService] {} salió de {} al unirse a {}", runnerId, other.getRoomCode(), target.getRoomCode());
                        leftRooms.add(other);
                    }
                }
            }
            return result;
        }
    }

    @Override
    public boolean removeRunnerFromRoom(String roomCode, String runnerId) {
        if (runnerId == null) return false;
        return getRoom(roomCode).map(room -> {
            boolean removed = room.removeRunner(runnerId);
            if (removed) {
                log.info("[RoomService] Corredor {} salió de la sala {}", runnerId, room.getRoomCode());
            }
            return removed;
        }).orElse(false);
    }

    @Override
    public RoomActionResult kickRunner(String roomCode, String requester, String hostKey, String runnerId) {
        Optional<Room> opt = getRoom(roomCode);
        if (opt.isEmpty()) {
            return RoomActionResult.NOT_FOUND;
        }
        Room room = opt.get();
        if (!room.canControl(requester, hostKey)) {
            return RoomActionResult.NOT_HOST;
        }
        RoomActionResult result = room.kick(runnerId);
        if (result == RoomActionResult.SUCCESS) {
            log.info("[RoomService] {} fue retirado de la sala {} por el host", runnerId, room.getRoomCode());
        }
        return result;
    }

    @Override
    public RoomActionResult startRace(String roomCode, String requester, String hostKey, double metaLat, double metaLon) {
        Optional<Room> opt = getRoom(roomCode);
        if (opt.isEmpty()) {
            return RoomActionResult.NOT_FOUND;
        }
        Room room = opt.get();
        if (!room.canControl(requester, hostKey)) {
            return RoomActionResult.NOT_HOST;
        }
        return room.startRace(metaLat, metaLon) ? RoomActionResult.SUCCESS : RoomActionResult.ALREADY_STARTED;
    }

    @Override
    public RoomActionResult moveMeta(String roomCode, String requester, String hostKey, double metaLat, double metaLon) {
        Optional<Room> opt = getRoom(roomCode);
        if (opt.isEmpty()) {
            return RoomActionResult.NOT_FOUND;
        }
        Room room = opt.get();
        if (!room.canControl(requester, hostKey)) {
            return RoomActionResult.NOT_HOST;
        }
        return room.moveMeta(metaLat, metaLon) ? RoomActionResult.SUCCESS : RoomActionResult.ALREADY_STARTED;
    }

    @Override
    public boolean canView(String roomCode, String requester, String hostKey) {
        return getRoom(roomCode).map(r -> r.canView(requester, hostKey)).orElse(false);
    }

    @Override
    public boolean isRaceInProgress(String roomCode) {
        return getRoom(roomCode)
                .map(r -> r.getStatus() == Room.RoomStatus.IN_PROGRESS)
                .orElse(false);
    }

    @Override
    public boolean isInActiveRoom(String username) {
        return rooms.values().stream()
                .anyMatch(r -> r.getStatus() != Room.RoomStatus.FINISHED && r.isMember(username));
    }

    /**
     * Ya no existe la sala fija "CAMPUS-ECI": nadie tenía su clave de host (no se podía iniciar), nunca
     * expiraba y bastaban 6 cuentas para dejarla llena para siempre. Todas las salas expiran igual.
     */
    @Override
    public List<String> cleanup(long now) {
        List<String> removed = new ArrayList<>();
        rooms.forEach((code, room) -> {
            boolean expired = switch (room.getStatus()) {
                case FINISHED -> now - Math.max(room.getFinishedAt(), room.getLastActivity()) > FINISHED_TTL_MS;
                case WAITING -> now - room.getLastActivity() > WAITING_IDLE_TTL_MS;
                case IN_PROGRESS -> now - room.getCreatedAt() > IN_PROGRESS_TTL_MS;
            };
            if (expired && rooms.remove(code, room)) {
                removed.add(code);
            }
        });
        if (!removed.isEmpty()) {
            log.info("[RoomService] Salas eliminadas por inactividad: {}", removed);
        }
        return removed;
    }

    private String generateUniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder("ECI-");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }
}
