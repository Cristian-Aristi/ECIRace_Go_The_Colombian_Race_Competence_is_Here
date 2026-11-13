package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.dto.CreateRoomRequest;
import edu.eci.arsw.eciracego.dto.CreatedRoom;
import edu.eci.arsw.eciracego.dto.RoomSummary;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.security.InputValidator;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Salas. Reglas de autorización:
 * <ul>
 *   <li>Crear: cualquiera (con límite por IP). Devuelve UNA vez la clave secreta del host.</li>
 *   <li>Unirse / salir: piloto autenticado, siempre como sí mismo.</li>
 *   <li>Iniciar / mover la meta: el piloto host o quien presente la clave del host (X-Host-Key).</li>
 *   <li>Ver estado, roster y posiciones: corredores de la sala y host.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private static final double DEFAULT_META_LAT = 4.782800;
    private static final double DEFAULT_META_LON = -74.043000;

    private final RoomService roomService;
    private final RaceManager raceManager;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomController(RoomService roomService, RaceManager raceManager, SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.raceManager = raceManager;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/create")
    public ResponseEntity<?> createRoom(@RequestBody(required = false) CreateRoomRequest request, HttpServletRequest http) {
        String hostUser = AuthenticatedUser.from(http).orElse(null);
        String preferred = request != null ? request.getPreferredCode() : null;
        try {
            return roomService.createRoom(hostUser, preferred)
                    .<ResponseEntity<?>>map(room -> ResponseEntity.status(HttpStatus.CREATED)
                            .body(new CreatedRoom(room, room.getHostKey())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "El código de sala ya está en uso, elige otro.", "code", "ROOM_TAKEN")));
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ROOM_LIMIT",
                    "Hay demasiadas salas activas en este momento. Inténtalo en unos minutos.");
        }
    }

    /** Lista pública: solo código, estado y ocupación (sin roster, host ni meta). */
    @GetMapping
    public List<RoomSummary> getAllRooms() {
        return roomService.getAllRooms().stream()
                .filter(r -> r.getStatus() != Room.RoomStatus.FINISHED)
                .sorted(Comparator.comparingLong(Room::getCreatedAt).reversed())
                .limit(30)
                .map(RoomSummary::of)
                .toList();
    }

    @GetMapping("/{code}")
    public Room getRoom(@PathVariable("code") String code, HttpServletRequest http) {
        return viewableRoom(code, http);
    }

    /** Última posición conocida de cada corredor de la sala (para tableros abiertos tarde). */
    @GetMapping("/{code}/positions")
    public List<Ubication> roomPositions(@PathVariable("code") String code, HttpServletRequest http) {
        Room room = viewableRoom(code, http);
        return raceManager.getRoomState(room.getRoomCode());
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<?> joinRoom(@PathVariable("code") String code, HttpServletRequest http) {
        String runnerId = AuthenticatedUser.require(http);
        RoomJoinResult result = roomService.registerRunnerInRoom(code, runnerId);

        return switch (result) {
            case OK -> {
                Room room = roomService.getRoom(code).orElseThrow();
                broadcastStatus(room);
                yield ResponseEntity.ok(room);
            }
            case ROOM_FULL -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "La sala ya tiene el máximo de " + Room.MAX_PLAYERS + " jugadores.", "code", "ROOM_FULL"));
            case RACE_ALREADY_STARTED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "La carrera ya inició, no puedes unirte ahora.", "code", "RACE_STARTED"));
            case ROOM_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "La sala no existe.", "code", "ROOM_NOT_FOUND"));
            case INVALID_NAME -> ResponseEntity.badRequest().body(Map.of("error", "Nombre de piloto inválido."));
        };
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable("code") String code, HttpServletRequest http) {
        String runnerId = AuthenticatedUser.require(http);
        if (roomService.removeRunnerFromRoom(code, runnerId)) {
            roomService.getRoom(code).ifPresent(this::broadcastStatus);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{code}/start")
    public ResponseEntity<?> startRace(@PathVariable("code") String code,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest http) {
        double metaLat = DEFAULT_META_LAT;
        double metaLon = DEFAULT_META_LON;
        if (body != null && body.get("metaLat") != null && body.get("metaLon") != null) {
            metaLat = InputValidator.requireCoordinate(body.get("metaLat"), true);
            metaLon = InputValidator.requireCoordinate(body.get("metaLon"), false);
        }
        String requester = AuthenticatedUser.from(http).orElse(null);
        RoomActionResult result = roomService.startRace(code, requester, AuthenticatedUser.hostKey(http), metaLat, metaLon);
        return actionResponse(code, result, "Solo el host puede iniciar la carrera.", "La carrera ya fue iniciada.");
    }

    @PutMapping("/{code}/meta")
    public ResponseEntity<?> moveMeta(@PathVariable("code") String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest http) {
        if (body == null) {
            throw ApiException.badRequest("Faltan las coordenadas de la meta.");
        }
        double lat = InputValidator.requireCoordinate(body.get("lat"), true);
        double lon = InputValidator.requireCoordinate(body.get("lon"), false);
        String requester = AuthenticatedUser.from(http).orElse(null);
        RoomActionResult result = roomService.moveMeta(code, requester, AuthenticatedUser.hostKey(http), lat, lon);
        return actionResponse(code, result, "Solo el host puede mover la meta.", "La meta no se puede mover con la carrera en curso.");
    }

    private ResponseEntity<?> actionResponse(String code, RoomActionResult result, String notHost, String conflict) {
        return switch (result) {
            case SUCCESS -> {
                Room room = roomService.getRoom(code).orElseThrow();
                broadcastStatus(room);
                yield ResponseEntity.ok(room);
            }
            case NOT_HOST -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", notHost, "code", "NOT_HOST"));
            case ALREADY_STARTED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", conflict, "code", "RACE_STARTED"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "La sala no existe.", "code", "ROOM_NOT_FOUND"));
        };
    }

    /** Notifica a los sockets suscritos a la sala (host y jugadores). */
    private void broadcastStatus(Room room) {
        messagingTemplate.convertAndSend("/topic/rooms/" + room.getRoomCode() + "/status", room);
    }

    private Room viewableRoom(String code, HttpServletRequest http) {
        Room room = roomService.getRoom(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "La sala no existe."));
        String user = AuthenticatedUser.from(http).orElse(null);
        if (!room.canView(user, AuthenticatedUser.hostKey(http))) {
            throw (user == null) ? ApiException.unauthorized()
                    : ApiException.forbidden("Solo los corredores y el host de la sala pueden verla.");
        }
        return room;
    }
}
