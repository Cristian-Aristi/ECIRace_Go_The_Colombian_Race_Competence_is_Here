package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.dto.CreateRoomRequest;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/create")
    public ResponseEntity<Room> createRoom(@RequestBody(required = false) CreateRoomRequest request) {
        String host = (request != null && request.getHostName() != null) ? request.getHostName() : "Host_Central";
        String preferredCode = (request != null) ? request.getPreferredCode() : null;
        Room created = roomService.createRoom(host, preferredCode);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
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
    public ResponseEntity<Room> joinRoom(
            @PathVariable("code") String code,
            @RequestBody(required = false) Map<String, String> body) {
        String runnerId = (body != null && body.containsKey("runnerId")) ? body.get("runnerId") : "PilotoAnonimo";
        roomService.registerRunnerInRoom(code, runnerId);
        return roomService.getRoom(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.CREATED).body(new Room(code, "AutoHost")));
    }
}