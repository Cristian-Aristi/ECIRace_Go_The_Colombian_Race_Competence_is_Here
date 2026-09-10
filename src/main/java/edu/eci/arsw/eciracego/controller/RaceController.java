package edu.eci.arsw.eciracego.controller;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.model.RaceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class RaceController {

    @Autowired
    private RaceManager raceManager;

    @MessageMapping("/telemetry")
    @SendTo("/topic/broadcast")
    public Ubication receiveCoordinate(Ubication ubication) {
        System.out.println("📍 Coordenada recibida de: " + ubication.getRunnerId() +
                " | Lat: " + ubication.getLatitude() + " | Lon: " + ubication.getLongitude());
        raceManager.updateUbication(ubication);

        return ubication;
    }
}
