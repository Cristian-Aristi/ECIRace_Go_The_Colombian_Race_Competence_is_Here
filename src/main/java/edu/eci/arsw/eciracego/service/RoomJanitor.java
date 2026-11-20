package edu.eci.arsw.eciracego.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Limpieza periódica: salas terminadas o abandonadas y posiciones viejas. Sin esto, cada sala
 * creada (y cada posición recibida) quedaba en memoria para siempre.
 */
@Component
public class RoomJanitor {

    private final RoomService roomService;
    private final RaceManager raceManager;

    public RoomJanitor(RoomService roomService, RaceManager raceManager) {
        this.roomService = roomService;
        this.raceManager = raceManager;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        List<String> removed = roomService.cleanup(now);
        raceManager.cleanup(removed, now);
    }
}
