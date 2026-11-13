package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class RaceManager {

    private static final Logger log = LoggerFactory.getLogger(RaceManager.class);

    /**
     * Tolerancia entre el reloj del teléfono y el del servidor. El tiempo transcurrido que se usa
     * para calcular la velocidad nunca puede superar el tiempo REAL que pasó en el servidor entre
     * dos paquetes más esta holgura (cubre la latencia de red y los paquetes que llegan en ráfaga).
     */
    static final long CLOCK_TOLERANCE_MS = 3_000;
    /** Una posición sin actualizar en este tiempo deja de estar en memoria. */
    static final long STALE_POSITION_MS = 10 * 60_000L;

    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    @Autowired
    private AntiCheatEngine antiCheatEngine;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    public RaceManager() {
    }

    /** Para pruebas: dependencias y reloj explícitos. */
    public RaceManager(AntiCheatEngine antiCheatEngine, RoomService roomService,
                       UserProfileRepository userProfileRepository, LongSupplier clock) {
        this.antiCheatEngine = antiCheatEngine;
        this.roomService = roomService;
        this.userProfileRepository = userProfileRepository;
        this.clock = clock;
    }

    /**
     * Actualiza la posición de un corredor de forma ATÓMICA (compute() sobre la clave del corredor).
     * <p>
     * Reglas anti-trampa:
     * <ul>
     *   <li>Coordenadas no finitas (NaN/Infinity) o fuera de rango se descartan.</li>
     *   <li>La hora de llegada la pone el servidor. El tiempo usado para la velocidad es el menor entre
     *       el que dice el teléfono y el que realmente pasó en el servidor (+ tolerancia): inflar o
     *       atrasar el "timestamp" ya no permite teletransportarse.</li>
     *   <li>Paquetes duplicados o desordenados (timestamp no creciente) se ignoran; ya no existe el
     *       caso "reloj muy atrasado" que reiniciaba la referencia sin validar.</li>
     * </ul>
     *
     * @return true si el paquete fue aceptado y debe retransmitirse.
     */
    public boolean updateUbication(Ubication newUbication) {
        if (newUbication == null || newUbication.getRunnerId() == null
                || !InputValidator.isValidCoordinate(newUbication.getLatitude(), newUbication.getLongitude())) {
            return false;
        }
        long now = clock.getAsLong();
        newUbication.setReceivedAt(now);
        if (newUbication.getTimestamp() <= 0) {
            newUbication.setTimestamp(now);
        }
        String id = newUbication.getRunnerId();
        boolean[] accepted = {false};

        raceState.compute(id, (key, previous) -> {
            // Primer paquete, o el corredor viene de otra sala: no hay referencia válida
            if (previous == null || !sameRoom(previous, newUbication)) {
                newUbication.setSpeed(0.0);
                newUbication.setSuspicious(false);
                accepted[0] = true;
                return newUbication;
            }

            long clientDelta = newUbication.getTimestamp() - previous.getTimestamp();
            if (clientDelta <= 0) {
                return previous; // duplicado o desordenado
            }
            long serverDelta = Math.max(0, now - previous.getReceivedAt());
            long effectiveDelta = Math.min(clientDelta, serverDelta + CLOCK_TOLERANCE_MS);

            double distance = antiCheatEngine.calculateDistanceMeters(
                    previous.getLatitude(), previous.getLongitude(),
                    newUbication.getLatitude(), newUbication.getLongitude());
            double speed = antiCheatEngine.speedKmH(distance, effectiveDelta);
            boolean suspicious = !Double.isFinite(speed) || antiCheatEngine.isSuspicious(speed);
            newUbication.setSpeed(Double.isFinite(speed) ? speed : 0.0);
            newUbication.setSuspicious(suspicious);

            if (suspicious) {
                return previous; // se conserva la última posición válida
            }
            accepted[0] = true;
            return newUbication;
        });

        return accepted[0];
    }

    private boolean sameRoom(Ubication a, Ubication b) {
        return a.getRoomCode() != null && a.getRoomCode().equalsIgnoreCase(b.getRoomCode());
    }

    public Collection<Ubication> getGlobalState() {
        return raceState.values();
    }

    public List<Ubication> getRoomState(String roomCode) {
        return raceState.values().stream()
                .filter(u -> roomCode != null && roomCode.equalsIgnoreCase(u.getRoomCode()))
                .toList();
    }

    public Ubication getUbication(String runnerId) {
        return raceState.get(runnerId);
    }

    /** Borra las posiciones de salas que ya no existen o que llevan mucho sin actualizarse. */
    public int cleanup(Collection<String> removedRooms, long now) {
        int before = raceState.size();
        raceState.values().removeIf(u ->
                (u.getRoomCode() != null && removedRooms.stream().anyMatch(c -> c.equalsIgnoreCase(u.getRoomCode())))
                        || now - u.getReceivedAt() > STALE_POSITION_MS
                        || !roomService.roomExists(u.getRoomCode()));
        return before - raceState.size();
    }

    public Room getRoom(String roomCode) {
        if (roomCode == null) {
            return null;
        }
        return roomService.getRoom(roomCode).orElse(null);
    }

    /** Suma la carrera a todos los pilotos de la sala y la victoria al ganador (lo llama el game loop una vez). */
    @Transactional
    public void saveResults(Room room) {
        if (room.getWinnerId() == null) {
            return;
        }
        for (String runnerId : room.getActiveRunners()) {
            userProfileRepository.findById(runnerId).ifPresent(perfil -> {
                perfil.registerRace(runnerId.equals(room.getWinnerId()));
                userProfileRepository.save(perfil);
            });
        }
        log.info("Resultados guardados. Sala {} | ganador: {}", room.getRoomCode(), room.getWinnerId());
    }
}
