package edu.eci.arsw.eciracego.service.impl;

import edu.eci.arsw.eciracego.model.Room;
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
    public Room createRoom(String hostName, String preferredCode) {
        String finalHost = (hostName != null && !hostName.trim().isEmpty()) ? hostName.trim() : "Host_Central";
        String roomCode;

        if (preferredCode != null && !preferredCode.trim().isEmpty()) {
            roomCode = preferredCode.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "");
            if (roomCode.isEmpty()) {
                roomCode = generateUniqueCode();
            }
        } else {
            roomCode = generateUniqueCode();
        }

        Room newRoom = new Room(roomCode, finalHost);
        rooms.put(roomCode, newRoom);
        log.info("[RoomService] Nueva sala creada con éxito: {} por host: {}", roomCode, finalHost);
        return newRoom;
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
    public void registerRunnerInRoom(String roomCode, String runnerId) {
        if (roomCode == null || runnerId == null) return;
        String code = roomCode.trim().toUpperCase();
        Room room = rooms.computeIfAbsent(code, k -> {
            log.info("[RoomService] Creando automáticamente sala {} para corredor {}", k, runnerId);
            return new Room(k, "AutoHost");
        });
        room.addRunner(runnerId);
        log.info("[RoomService] Corredor {} registrado en sala {}", runnerId, code);
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