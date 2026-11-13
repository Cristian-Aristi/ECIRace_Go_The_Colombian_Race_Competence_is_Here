package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Ubication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class GameLoopService {

    private static final Logger log = LoggerFactory.getLogger(GameLoopService.class);
    private static final double COLLISION_THRESHOLD_METERS = 1.5;
    private static final double COLLISION_DECELERATION_FACTOR = 0.8;

    private final RaceManager raceManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final AntiCheatEngine antiCheatEngine;

    private final ReentrantLock gridLock = new ReentrantLock();

    @Autowired
    public GameLoopService(RaceManager raceManager,
                           SimpMessagingTemplate messagingTemplate,
                           AntiCheatEngine antiCheatEngine) {
        this.raceManager = raceManager;
        this.messagingTemplate = messagingTemplate;
        this.antiCheatEngine = antiCheatEngine;
    }

    @Scheduled(fixedRate = 100)
    public void processGameTick() {
        Collection<Ubication> currentState = raceManager.getGlobalState();
        if (currentState == null || currentState.isEmpty()) {
            return;
        }

        gridLock.lock();
        try {
            checkFinishLine(currentState);
            // Las colisiones solo ocurren entre corredores de la misma sala: se agrupan para no
            // comparar todos contra todos (antes O(n²) sobre TODAS las salas en cada tick).
            Map<String, List<Ubication>> byRoom = currentState.stream()
                    .filter(u -> u.getRoomCode() != null)
                    .collect(Collectors.groupingBy(u -> u.getRoomCode().toUpperCase()));
            byRoom.values().forEach(this::resolveCollisions);
            // Ya no se difunde /topic/gamestate: mandaba las posiciones GPS de TODAS las salas a
            // cualquiera que se suscribiera. Cada sala recibe solo lo suyo en /topic/rooms/{code}.
        } finally {
            gridLock.unlock(); // Guaranteed lock release
        }
    }

    private void checkFinishLine(Collection<Ubication> runners) {
        for (Ubication ubication : runners) {
            String roomCode = ubication.getRoomCode();
            if (roomCode == null) continue;

            // Obtenemos la sala desde el RaceManager (asegúrate de tener este método getRoom)
            edu.eci.arsw.eciracego.model.Room room = raceManager.getRoom(roomCode);

            // Solo evaluamos si la sala existe y la carrera está en curso
            if (room != null && room.getStatus() == edu.eci.arsw.eciracego.model.Room.RoomStatus.IN_PROGRESS) {

                double distanceToMeta = calculateDistanceMeters(
                        ubication.getLatitude(), ubication.getLongitude(),
                        room.getMetaLat(), room.getMetaLon()
                );

                // Si entra al radio de la meta (Ej. 15 metros)
                if (distanceToMeta <= room.getRadioMetaMetros()) {

                    // Intento atómico (Thread-Safe) gracias al synchronized que pusimos en Room
                    boolean isWinner = room.declareWinner(ubication.getRunnerId());

                    if (isWinner) {
                        log.info("🏁 GANADOR DETECTADO: {} en sala {}", ubication.getRunnerId(), roomCode);

                        // Notificar a los clientes que la carrera terminó
                        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/status", room);

                        // Suma la carrera a todos los pilotos de la sala y la victoria al ganador.
                        // declareWinner() es atómico, así que esto se ejecuta una sola vez por carrera.
                        try {
                            raceManager.saveResults(room);
                        } catch (RuntimeException e) {
                            log.warn("No se pudieron guardar los resultados de la sala {}: {}", roomCode, e.getMessage());
                        }
                    }
                }
            }
        }
    }
    public void resolveCollisions(Collection<Ubication> runners) {
        if (runners.size() < 2) {
            return;
        }

        Ubication[] array = runners.toArray(new Ubication[0]);
        for (int i = 0; i < array.length; i++) {
            for (int j = i + 1; j < array.length; j++) {
                Ubication r1 = array[i];
                Ubication r2 = array[j];

                if (r1.getRoomCode() != null && r1.getRoomCode().equalsIgnoreCase(r2.getRoomCode())) {
                    double distance = antiCheatEngine.calculateDistanceMeters(
                            r1.getLatitude(), r1.getLongitude(),
                            r2.getLatitude(), r2.getLongitude()
                    );

                    if (distance < COLLISION_THRESHOLD_METERS) {
                        applyCollisionPhysics(r1, r2);
                    }
                }
            }
        }
    }

    private void applyCollisionPhysics(Ubication r1, Ubication r2) {
        double newSpeed1 = Math.max(0.0, r1.getSpeed() * COLLISION_DECELERATION_FACTOR);
        double newSpeed2 = Math.max(0.0, r2.getSpeed() * COLLISION_DECELERATION_FACTOR);

        r1.setSpeed(newSpeed1);
        r2.setSpeed(newSpeed2);

        log.info("[GameLoop] Collision resolved between runner {} and runner {} in room {}",
                r1.getRunnerId(), r2.getRunnerId(), r1.getRoomCode());
    }

    public boolean isGridLockHeld() {
        return gridLock.isLocked();
    }

    public double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final int RADIO_TIERRA = 6371000; // Radio de la Tierra en metros
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return RADIO_TIERRA * c;
    }
}