package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.Executor;

@Controller
public class RaceController {

    private static final Logger log = LoggerFactory.getLogger(RaceController.class);

    private final RaceManager raceManager;
    private final RoomService roomService;
    private final Executor raceTaskExecutor;
    private final SimpMessagingTemplate messagingTemplate;

    public RaceController(RaceManager raceManager, RoomService roomService,
                          @Qualifier("raceTaskExecutor") Executor raceTaskExecutor,
                          SimpMessagingTemplate messagingTemplate) {
        this.raceManager = raceManager;
        this.roomService = roomService;
        this.raceTaskExecutor = raceTaskExecutor;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Telemetría GPS. El corredor es SIEMPRE el usuario autenticado de la sesión STOMP
     * (StompSecurityInterceptor); el "runnerId" que mande el cliente se ignora.
     */
    @MessageMapping("/telemetry")
    public void receiveCoordinate(Ubication ubication, Principal principal) {
        if (principal == null || ubication == null) {
            return;
        }
        ubication.setRunnerId(principal.getName());
        raceTaskExecutor.execute(() -> process(ubication));
    }

    void process(Ubication ubication) {
        String room = ubication.getRoomCode();
        Optional<Room> roomOpt = roomService.getRoom(room);
        if (roomOpt.isEmpty() || roomOpt.get().getStatus() != Room.RoomStatus.IN_PROGRESS) {
            log.debug("Telemetría rechazada: la sala {} no existe o no está en carrera", room);
            return;
        }
        if (!roomOpt.get().isMember(ubication.getRunnerId())) {
            log.debug("Telemetría rechazada: {} no está inscrito en {}", ubication.getRunnerId(), room);
            return;
        }
        if (!raceManager.updateUbication(ubication)) {
            log.debug("Telemetría descartada para {} ({} km/h)", ubication.getRunnerId(), ubication.getSpeed());
            return;
        }
        // Solo al canal de la sala (antes también se enviaba a /topic/broadcast)
        messagingTemplate.convertAndSend("/topic/rooms/" + roomOpt.get().getRoomCode(), ubication);
    }
}
