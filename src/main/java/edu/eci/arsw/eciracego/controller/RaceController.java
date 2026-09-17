package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.model.RaceManager;
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
    @Qualifier("raceTaskExecutor")
    private Executor raceTaskExecutor;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/telemetry")
    public void receiveCoordinate(Ubication ubication) {
        raceTaskExecutor.execute(() -> {
            System.out.println("📍 [" + Thread.currentThread().getName() + "] Coordenada recibida de: " + ubication.getRunnerId() +
                    " | Lat: " + ubication.getLatitude() + " | Lon: " + ubication.getLongitude());

            raceManager.updateUbication(ubication);

            messagingTemplate.convertAndSend("/topic/broadcast", ubication);
        });
    }
}
