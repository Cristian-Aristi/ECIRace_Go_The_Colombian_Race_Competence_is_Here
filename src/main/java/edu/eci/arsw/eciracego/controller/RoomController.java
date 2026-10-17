package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.dto.CreateRoomRequest;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin(origins = "*")
public class RoomController {

    private final RoomService roomService;

    @Autowired
    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @PostMapping("/create")
    public ResponseEntity<?> createRoom(@RequestBody(required = false) CreateRoomRequest request) {
        String host = (request != null && request.getHostName() != null && !request.getHostName().isBlank())
                ? request.getHostName() : "Host_Central";
        String preferredCode = (request != null) ? request.getPreferredCode() : null;

        return roomService.createRoom(host, preferredCode)
                .<ResponseEntity<?>>map(room -> ResponseEntity.status(HttpStatus.CREATED).body(room))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "El código de sala ya está en uso, elige otro.")));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Room> getRoom(@PathVariable("code") String code) {
        return roomService.getRoom(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<Collection<Room>> getAllRooms() {
        return ResponseEntity.ok(roomService.getAllRooms());
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<?> joinRoom(@PathVariable("code") String code,
                                    @RequestBody(required = false) Map<String, String> body) {
        String runnerId = (body != null) ? body.get("runnerId") : null;
        RoomJoinResult result = roomService.registerRunnerInRoom(code, runnerId);

        return switch (result) {
            case OK -> roomService.getRoom(code).<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
            case ROOM_FULL -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "La sala ya tiene el máximo de " + Room.MAX_PLAYERS + " jugadores."));
            case RACE_ALREADY_STARTED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "La carrera ya inició, no puedes unirte ahora."));
            case ROOM_NOT_FOUND -> ResponseEntity.notFound().build();
            case INVALID_NAME -> ResponseEntity.badRequest().body(Map.of("error", "Nombre de piloto inválido."));
        };
    }

    @PostMapping("/{code}/start")
    public ResponseEntity<?> startRace(@PathVariable("code") String code,
                                       @RequestBody Map<String, Object> body) {
        String requester = (body != null && body.get("hostName") != null) ? body.get("hostName").toString() : null;

        // Coordenadas por defecto (Entrada ECI) por si alguien inicia desde el lobby sin mapa
        double metaLat = 4.782800;
        double metaLon = -74.043000;

        if (body != null && body.containsKey("metaLat") && body.containsKey("metaLon")) {
            metaLat = Double.parseDouble(body.get("metaLat").toString());
            metaLon = Double.parseDouble(body.get("metaLon").toString());
        }

        // Pasamos las coordenadas al servicio
        RoomActionResult result = roomService.startRace(code, requester, metaLat, metaLon);

        return switch (result) {
            case SUCCESS -> {
                Room room = roomService.getRoom(code).orElseThrow();
                // Notifica a TODOS los sockets suscritos a esta sala (host y jugadores)
                messagingTemplate.convertAndSend("/topic/rooms/" + room.getRoomCode() + "/status", room);
                yield ResponseEntity.ok(room);
            }
            case NOT_HOST -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Solo el host puede iniciar la carrera."));
            case ALREADY_STARTED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "La carrera ya fue iniciada."));
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }
}