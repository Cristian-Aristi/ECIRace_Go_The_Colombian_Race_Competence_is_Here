package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.security.InputValidator;
import edu.eci.arsw.eciracego.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    public static final String DEFAULT_ROOM = "CAMPUS-ECI";
    /** Sin "0/O" ni "1/I" para que el código se pueda dictar sin confusiones. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    static final long FINISHED_TTL_MS = 15 * 60_000L;
    static final long WAITING_IDLE_TTL_MS = 2 * 60 * 60_000L;
    static final long IN_PROGRESS_TTL_MS = 60 * 60_000L;
    public static final int DEFAULT_MAX_ROOMS = 2000;

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Máximo de salas simultáneas en memoria (antes no había tope y se podían crear salas en bucle). */
    private final int maxRooms;

    public RoomServiceImpl() {
        this(DEFAULT_MAX_ROOMS);
    }

    @Autowired
    public RoomServiceImpl(@Value("${ecirace.rooms.max:" + DEFAULT_MAX_ROOMS + "}") int maxRooms) {
        this.maxRooms = Math.max(1, maxRooms);
        Room campusDefault = new Room(DEFAULT_ROOM, null);
        rooms.put(campusDefault.getRoomCode(), campusDefault);
        log.info("[RoomService] Sala predeterminada creada: {}", campusDefault.getRoomCode());
    }

    @Override
    public Optional<Room> createRoom(String hostUser, String preferredCode) {
        if (rooms.size() >= maxRooms) {
            throw new IllegalStateException("Se alcanzó el máximo de salas activas");
        }
        String roomCode = (preferredCode != null && !preferredCode.isBlank())
                ? InputValidator.requireRoomCode(preferredCode)
                : generateUniqueCode();

        Room newRoom = new Room(roomCode, hostUser);
        // putIfAbsent es ATÓMICO: dos hosts creando el mismo código a la vez no se pisan
        Room existing = rooms.putIfAbsent(roomCode, newRoom);
        if (existing != null) {
            log.warn("[RoomService] Código de sala {} ya está en uso", roomCode);
            return Optional.empty();
        }
        log.info("[RoomService] Sala creada: {} | Host: {}", roomCode, newRoom.getHostName());
        return Optional.of(newRoom);
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
    public RoomJoinResult registerRunnerInRoom(String roomCode, String runnerId) {
        Optional<Room> room = getRoom(roomCode);
        if (room.isEmpty()) {
            return RoomJoinResult.ROOM_NOT_FOUND;
        }
        RoomJoinResult result = room.get().addRunner(runnerId);
        log.info("[RoomService] {} intenta unirse a {} -> {}", runnerId, room.get().getRoomCode(), result);
        return result;
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

    @Override
    public List<String> cleanup(long now) {
        List<String> removed = new ArrayList<>();
        rooms.forEach((code, room) -> {
            if (DEFAULT_ROOM.equals(code)) {
                return;
            }
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
