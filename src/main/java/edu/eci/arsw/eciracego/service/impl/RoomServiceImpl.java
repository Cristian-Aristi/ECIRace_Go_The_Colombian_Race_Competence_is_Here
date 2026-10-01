package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public RoomServiceImpl() {
        Room campusDefault = new Room("CAMPUS-ECI", "Central Campus ECI");
        rooms.put(campusDefault.getRoomCode(), campusDefault);
        log.info("[RoomService] Sala predeterminada creada: {}", campusDefault.getRoomCode());
    }

    @Override
    public Optional<Room> createRoom(String hostName, String preferredCode) {
        String finalHost = (hostName != null && !hostName.trim().isEmpty()) ? hostName.trim() : "Host_Central";
        String roomCode = normalizeOrGenerate(preferredCode);

        Room newRoom = new Room(roomCode, finalHost);
        // putIfAbsent es ATÓMICO: soluciona la condición de carrera de dos
        // hosts creando el mismo código al mismo tiempo, y evita sobrescribir
        // una sala existente.
        Room existing = rooms.putIfAbsent(roomCode, newRoom);
        if (existing != null) {
            log.warn("[RoomService] Código de sala {} ya está en uso", roomCode);
            return Optional.empty();
        }
        log.info("[RoomService] 🆕 Sala creada: {} | Host: {}", roomCode, finalHost);
        return Optional.of(newRoom);
    }

    private String normalizeOrGenerate(String preferredCode) {
        if (preferredCode != null && !preferredCode.trim().isEmpty()) {
            String normalized = preferredCode.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "");
            if (!normalized.isEmpty()) return normalized;
        }
        return generateUniqueCode();
    }

    @Override
    public Optional<Room> getRoom(String roomCode) {
        if (roomCode == null) return Optional.empty();
        return Optional.ofNullable(rooms.get(roomCode.trim().toUpperCase()));
    }

    @Override
    public Collection<Room> getAllRooms() {
        return Collections.unmodifiableCollection(rooms.values());
    }

    @Override
    public boolean roomExists(String roomCode) {
        if (roomCode == null) return false;
        return rooms.containsKey(roomCode.trim().toUpperCase());
    }

    @Override
    public RoomJoinResult registerRunnerInRoom(String roomCode, String runnerId) {
        if (roomCode == null) return RoomJoinResult.ROOM_NOT_FOUND;
        Room room = rooms.get(roomCode.trim().toUpperCase());
        if (room == null) {
            return RoomJoinResult.ROOM_NOT_FOUND;
        }
        RoomJoinResult result = room.addRunner(runnerId);
        log.info("[RoomService] 🏎️ {} intenta unirse a {} -> {}", runnerId, roomCode, result);
        return result;
    }

    @Override
    public RoomActionResult startRace(String roomCode, String requester, double metaLat, double metaLon) {

        Room room = this.rooms.get(roomCode);

        if (room == null) {
            return RoomActionResult.NOT_FOUND;
        }

        boolean success = room.startRace(requester, metaLat, metaLon);

        if (success) {
            return RoomActionResult.SUCCESS;
        } else if (!room.isHost(requester)) {
            return RoomActionResult.NOT_HOST;
        } else {
            return RoomActionResult.ALREADY_STARTED;
        }
    }

    @Override
    public boolean isRaceInProgress(String roomCode) {
        return getRoom(roomCode)
                .map(r -> r.getStatus() == Room.RoomStatus.IN_PROGRESS)
                .orElse(false);
    }

    @Override
    public void removeRunnerFromRoom(String roomCode, String runnerId) {
        if (roomCode == null || runnerId == null) return;
        Room room = rooms.get(roomCode.trim().toUpperCase());
        if (room != null) {
            room.removeRunner(runnerId);
            log.info("[RoomService] Corredor {} retirado de sala {}", runnerId, roomCode);
        }
    }

    private String generateUniqueCode() {
        String code;
        do {
            int num = 1000 + random.nextInt(9000);
            code = "ECI-" + num;
        } while (rooms.containsKey(code));
        return code;
    }
}