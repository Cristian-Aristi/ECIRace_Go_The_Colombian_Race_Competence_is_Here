package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.RaceManager;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.GameLoopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameLoopServiceTest {

    private GameLoopService gameLoopService;

    @BeforeEach
    void setUp() {
        RaceManager raceManager = Mockito.mock(RaceManager.class);
        SimpMessagingTemplate messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        AntiCheatEngine antiCheatEngine = new AntiCheatEngine();
        gameLoopService = new GameLoopService(raceManager, messagingTemplate, antiCheatEngine);
    }

    @Test
    void shouldResolveCollisionWhenRunnersAreWithinThreshold() {
        Ubication r1 = new Ubication("runnerA", 4.782800, -74.043000);
        r1.setSpeed(20.0);
        r1.setRoomCode("ROOM-1");

        Ubication r2 = new Ubication("runnerB", 4.782800, -74.043000);
        r2.setSpeed(20.0);
        r2.setRoomCode("ROOM-1");

        gameLoopService.resolveCollisions(List.of(r1, r2));

        assertEquals(16.0, r1.getSpeed(), 0.01, "Speed of runner A should be reduced to 16.0 km/h");
        assertEquals(16.0, r2.getSpeed(), 0.01, "Speed of runner B should be reduced to 16.0 km/h");
    }

    @Test
    void shouldNotResolveCollisionWhenRunnersAreFarApart() {
        Ubication r1 = new Ubication("runnerA", 4.782800, -74.043000);
        r1.setSpeed(20.0);
        r1.setRoomCode("ROOM-1");

        Ubication r2 = new Ubication("runnerB", 4.785000, -74.043000);
        r2.setSpeed(20.0);
        r2.setRoomCode("ROOM-1");

        gameLoopService.resolveCollisions(List.of(r1, r2));

        assertEquals(20.0, r1.getSpeed(), "Speed should remain unaffected");
        assertEquals(20.0, r2.getSpeed(), "Speed should remain unaffected");
    }
}