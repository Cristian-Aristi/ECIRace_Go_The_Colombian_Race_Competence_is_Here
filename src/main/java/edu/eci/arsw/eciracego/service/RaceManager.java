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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class RaceManager {

    private static final Logger log = LoggerFactory.getLogger(RaceManager.class);

    /**
     * Cuánto puede adelantarse el reloj del teléfono al del servidor en TODO el recorrido (cubre la
     * latencia del primer paquete y los paquetes que llegan en ráfaga tras un corte de red).
     */
    static final long CLOCK_TOLERANCE_MS = 3_000;
    /**
     * Distancia mínima entre la meta y la PRIMERA posición de un corredor en la carrera. Sin esto, el
     * primer paquete se aceptaba en cualquier lugar (no hay referencia para medir la velocidad) y bastaba
     * con mandarlo dentro de la meta para ganar al instante (auditoría Sprint 9, segunda ronda).
     */
    public static final double MIN_START_DISTANCE_M = 40.0;
    /*
     * Cuándo una carrera cuenta para la clasificación (auditoría Sprint 9, tercera ronda: una sola cuenta
     * creaba su sala, la iniciaba y "trotaba" 30 m hasta la meta, sumando 3 victorias en 19 s). Si no se
     * cumple, la carrera tiene ganador igual, pero es "de práctica" y no suma carreras ni victorias.
     */
    /** Pilotos distintos que deben haber enviado posición en la carrera. */
    public static final int MIN_RANKED_RUNNERS = 2;
    /** Duración mínima (servidor) entre la largada y la llegada del ganador. */
    public static final long MIN_RANKED_RACE_MS = 60_000L;
    /** Distancia mínima entre la primera posición del ganador y la meta. */
    public static final double MIN_RANKED_START_DISTANCE_M = 150.0;

    /** Una posición sin actualizar en este tiempo deja de estar en memoria (salvo si su carrera sigue en curso). */
    static final long STALE_POSITION_MS = 10 * 60_000L;

    /** Última posición válida de cada corredor EN CADA SALA (clave "SALA|piloto"). */
    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final AntiCheatEngine antiCheatEngine;
    private final RoomService roomService;
    private final UserProfileRepository userProfileRepository;

    @Autowired
    public RaceManager(AntiCheatEngine antiCheatEngine, RoomService roomService,
                       UserProfileRepository userProfileRepository) {
        this(antiCheatEngine, roomService, userProfileRepository, System::currentTimeMillis);
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
     * Actualiza la posición de un corredor de forma ATÓMICA (compute() sobre la clave sala+corredor).
     * <p>
     * Reglas anti-trampa:
     * <ul>
     *   <li>Solo se acepta telemetría de una sala existente, con la carrera en curso, y de un corredor
     *       inscrito en ella.</li>
     *   <li>Coordenadas no finitas (NaN/Infinity) o fuera de rango se descartan.</li>
     *   <li>La primera posición de la carrera debe estar al menos a {@link #MIN_START_DISTANCE_M} de la meta.</li>
     *   <li>El recorrido se guarda por sala: cambiar el "roomCode" entre dos salas ya no reinicia la
     *       referencia (antes permitía teletransportarse).</li>
     *   <li>El tiempo usado para la velocidad sale del reloj del teléfono, pero ese reloj nunca puede ir
     *       más de {@link #CLOCK_TOLERANCE_MS} por delante del tiempo real que pasó en el servidor desde
     *       el primer paquete. Antes la tolerancia se sumaba en cada paquete: adelantando el timestamp
     *       3 s por paquete se avanzaban ~25 m cada 200 ms (más de 400 km/h).</li>
     *   <li>Paquetes duplicados o desordenados (timestamp no creciente) se ignoran.</li>
     * </ul>
     *
     * @return true si el paquete fue aceptado y debe retransmitirse.
     */
    public boolean updateUbication(Ubication newUbication) {
        if (newUbication == null || newUbication.getRunnerId() == null
                || !InputValidator.isValidCoordinate(newUbication.getLatitude(), newUbication.getLongitude())) {
            return false;
        }
        Optional<Room> roomOpt = roomService.getRoom(newUbication.getRoomCode());
        if (roomOpt.isEmpty()) {
            return false;
        }
        Room room = roomOpt.get();
        String runnerId = newUbication.getRunnerId();
        if (room.getStatus() != Room.RoomStatus.IN_PROGRESS || !room.isMember(runnerId)) {
            return false;
        }
        newUbication.setRoomCode(room.getRoomCode());

        long now = clock.getAsLong();
        newUbication.setReceivedAt(now);
        if (newUbication.getTimestamp() <= 0) {
            newUbication.setTimestamp(now);
        }
        boolean[] accepted = {false};

        raceState.compute(key(room.getRoomCode(), runnerId), (key, previous) -> {
            if (previous == null) {
                double toMeta = antiCheatEngine.calculateDistanceMeters(
                        newUbication.getLatitude(), newUbication.getLongitude(), room.getMetaLat(), room.getMetaLon());
                if (!(toMeta >= MIN_START_DISTANCE_M)) {
                    log.info("Salida rechazada: {} empezó a {} m de la meta en {}", runnerId, Math.round(toMeta), room.getRoomCode());
                    return null; // sin referencia válida: no se guarda nada
                }
                newUbication.setTrack(newUbication.getTimestamp(), now, newUbication.getTimestamp(), toMeta);
                newUbication.setSpeed(0.0);
                newUbication.setSuspicious(false);
                accepted[0] = true;
                return newUbication;
            }

            if (newUbication.getTimestamp() <= previous.getTimestamp()) {
                return previous; // duplicado o desordenado
            }
            // Hasta dónde puede llegar el reloj del teléfono según el tiempo real del servidor
            long clientLimit = previous.getTrackClientStart() + (now - previous.getTrackServerStart()) + CLOCK_TOLERANCE_MS;
            long effective = Math.min(newUbication.getTimestamp(), clientLimit);
            long effectiveDelta = effective - previous.getEffectiveTime();
            if (effectiveDelta <= 0) {
                return previous; // el teléfono va demasiado adelantado: se espera a que pase tiempo real
            }

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
            newUbication.setTrack(previous.getTrackClientStart(), previous.getTrackServerStart(), effective,
                    previous.getStartDistanceToMeta());
            accepted[0] = true;
            return newUbication;
        });

        return accepted[0];
    }

    private static String key(String roomCode, String runnerId) {
        return roomCode + "|" + runnerId;
    }

    public Collection<Ubication> getGlobalState() {
        return raceState.values();
    }

    public List<Ubication> getRoomState(String roomCode) {
        return raceState.values().stream()
                .filter(u -> roomCode != null && roomCode.equalsIgnoreCase(u.getRoomCode()))
                .toList();
    }

    public Ubication getUbication(String roomCode, String runnerId) {
        if (roomCode == null || runnerId == null) {
            return null;
        }
        return raceState.get(key(roomCode.trim().toUpperCase(java.util.Locale.ROOT), runnerId));
    }

    /**
     * Borra las posiciones de salas que ya no existen y las que llevan mucho sin actualizarse. Las de una
     * carrera EN CURSO se conservan: si se borraran, el siguiente paquete volvería a ser "el primero" y
     * podría aparecer en cualquier lugar.
     */
    public int cleanup(Collection<String> removedRooms, long now) {
        int before = raceState.size();
        raceState.values().removeIf(u -> {
            if (u.getRoomCode() != null && removedRooms.stream().anyMatch(c -> c.equalsIgnoreCase(u.getRoomCode()))) {
                return true;
            }
            Optional<Room> room = roomService.getRoom(u.getRoomCode());
            if (room.isEmpty()) {
                return true;
            }
            return room.get().getStatus() != Room.RoomStatus.IN_PROGRESS && now - u.getReceivedAt() > STALE_POSITION_MS;
        });
        return before - raceState.size();
    }

    public Room getRoom(String roomCode) {
        if (roomCode == null) {
            return null;
        }
        return roomService.getRoom(roomCode).orElse(null);
    }

    /**
     * true si la carrera terminada cuenta para la clasificación: al menos {@link #MIN_RANKED_RUNNERS}
     * pilotos enviaron posición, duró al menos {@link #MIN_RANKED_RACE_MS} y el ganador salió a
     * {@link #MIN_RANKED_START_DISTANCE_M} o más de la meta.
     */
    public boolean isRanked(Room room) {
        if (room.getWinnerId() == null || room.getStartedAt() <= 0) {
            return false;
        }
        long runnersWithTelemetry = getRoomState(room.getRoomCode()).stream()
                .map(Ubication::getRunnerId)
                .filter(room::isMember)
                .distinct()
                .count();
        Ubication winner = getUbication(room.getRoomCode(), room.getWinnerId());
        long duration = room.getFinishedAt() - room.getStartedAt();
        return runnersWithTelemetry >= MIN_RANKED_RUNNERS
                && duration >= MIN_RANKED_RACE_MS
                && winner != null
                && winner.getStartDistanceToMeta() >= MIN_RANKED_START_DISTANCE_M;
    }

    /**
     * Decide si la carrera cuenta ({@link #isRanked}) y, solo en ese caso, suma la carrera a todos los
     * pilotos de la sala y la victoria al ganador. Lo llama el game loop una vez por carrera.
     */
    @Transactional
    public void saveResults(Room room) {
        if (room.getWinnerId() == null) {
            return;
        }
        boolean ranked = isRanked(room);
        room.setRanked(ranked);
        if (!ranked) {
            log.info("Carrera de práctica en {} (ganador {}): no suma a la clasificación", room.getRoomCode(), room.getWinnerId());
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
