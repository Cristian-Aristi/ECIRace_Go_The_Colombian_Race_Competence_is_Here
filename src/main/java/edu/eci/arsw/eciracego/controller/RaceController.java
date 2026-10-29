package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@Controller
public class RaceController {

    @Autowired
    private RaceManager raceManager;

    @Autowired
    private RoomService roomService;

    @Autowired
    @Qualifier("raceTaskExecutor")
    private Executor raceTaskExecutor;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/telemetry")
    public void receiveCoordinate(Ubication ubication) {
        raceTaskExecutor.execute(() -> {
            String room = (ubication.getRoomCode() != null && !ubication.getRoomCode().trim().isEmpty())
                    ? ubication.getRoomCode().trim().toUpperCase()
                    : "CAMPUS-ECI";
            ubication.setRoomCode(room);

            Optional<Room> roomOpt = roomService.getRoom(room);
            if (roomOpt.isEmpty() || roomOpt.get().getStatus() != Room.RoomStatus.IN_PROGRESS) {
                System.out.printf("[RaceController] [%s] Telemetry rejected: Room %s does not exist or race is not in progress%n",
                        Thread.currentThread().getName(), room);
                return;
            }

            if (ubication.getRunnerId() == null || !roomOpt.get().getActiveRunners().contains(ubication.getRunnerId())) {
                System.out.printf("[RaceController] [%s] Telemetry rejected: Runner %s is not registered in room %s%n",
                        Thread.currentThread().getName(), ubication.getRunnerId(), room);
                return;
            }

            boolean accepted = raceManager.updateUbication(ubication);
            if (!accepted) {
                System.out.printf("[RaceController] [%s] Telemetry discarded: Anti-cheat anomaly detected for runner %s (Speed: %.1f km/h)%n",
                        Thread.currentThread().getName(),
                        ubication.getRunnerId(),
                        ubication.getSpeed());
                return;
            }

            System.out.printf("[RaceController] [%s] Room: %s | Runner: %s | Speed: %.1f km/h | Status: ACCEPTED%n",
                    Thread.currentThread().getName(),
                    room,
                    ubication.getRunnerId(),
                    ubication.getSpeed());

            // Solo al canal de la sala: antes también se enviaba a /topic/broadcast y cada
            // dashboard recibía (y procesaba) todo dos veces, incluidas las salas ajenas.
            messagingTemplate.convertAndSend("/topic/rooms/" + room, ubication);
        });
    }

    /**
     * Última posición conocida de cada corredor de la sala. Un tablero que se abre (o se recarga)
     * después de que el corredor empezó a transmitir, o un corredor quieto cuyo GPS ya no emite
     * fixes nuevos, de otro modo no aparecería en el mapa hasta el siguiente paquete.
     */
    @GetMapping("/api/rooms/{code}/positions")
    @ResponseBody
    @CrossOrigin(origins = "*")
    public List<Ubication> roomPositions(@PathVariable("code") String code) {
        String room = code.trim().toUpperCase();
        return raceManager.getGlobalState().stream()
                .filter(u -> room.equalsIgnoreCase(u.getRoomCode()))
                .collect(java.util.stream.Collectors.toList());
    }
}
