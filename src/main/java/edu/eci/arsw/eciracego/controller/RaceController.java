package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.model.RaceManager;
import edu.eci.arsw.eciracego.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

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
            if (ubication.getRunnerId() != null) {
                roomService.registerRunnerInRoom(room, ubication.getRunnerId());
            }
            raceManager.updateUbication(ubication);
            String statusAntiCheat = ubication.isSuspicious() ? "ANOMALÍA / TELETRANSPORTE" : "LIMPIO";
            System.out.printf("📍 [%s] Sala: %s | Corredor: %s | Vel: %.1f km/h | Anti-Cheat: %s%n",
                    Thread.currentThread().getName(),
                    room,
                    ubication.getRunnerId(),
                    ubication.getSpeed(),
                    statusAntiCheat);
            messagingTemplate.convertAndSend("/topic/broadcast", ubication);
            messagingTemplate.convertAndSend("/topic/rooms/" + room, ubication);
        });
    }
}
