package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.RaceManager;
import edu.eci.arsw.eciracego.model.Ubication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class GameLoopService {

    private static final Logger log = LoggerFactory.getLogger(GameLoopService.class);
    private static final double COLLISION_THRESHOLD_METERS = 1.5;
    private static final double COLLISION_DECELERATION_FACTOR = 0.8;

    private final RaceManager raceManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final AntiCheatEngine antiCheatEngine;

    private final ReentrantLock gridLock = new ReentrantLock();

    @Autowired
    public GameLoopService(RaceManager raceManager,
                           SimpMessagingTemplate messagingTemplate,
                           AntiCheatEngine antiCheatEngine) {
        this.raceManager = raceManager;
        this.messagingTemplate = messagingTemplate;
        this.antiCheatEngine = antiCheatEngine;
    }

    @Scheduled(fixedRate = 33)
    public void processGameTick() {
        Collection<Ubication> currentState = raceManager.getGlobalState();
        if (currentState == null || currentState.isEmpty()) {
            return;
        }

        gridLock.lock();
        try {
            resolveCollisions(currentState);
            messagingTemplate.convertAndSend("/topic/gamestate", currentState);
        } finally {
            gridLock.unlock(); // Guaranteed lock release
        }
    }

    public void resolveCollisions(Collection<Ubication> runners) {
        if (runners.size() < 2) {
            return;
        }

        Ubication[] array = runners.toArray(new Ubication[0]);
        for (int i = 0; i < array.length; i++) {
            for (int j = i + 1; j < array.length; j++) {
                Ubication r1 = array[i];
                Ubication r2 = array[j];

                if (r1.getRoomCode() != null && r1.getRoomCode().equalsIgnoreCase(r2.getRoomCode())) {
                    double distance = antiCheatEngine.calculateDistanceMeters(
                            r1.getLatitude(), r1.getLongitude(),
                            r2.getLatitude(), r2.getLongitude()
                    );

                    if (distance < COLLISION_THRESHOLD_METERS) {
                        applyCollisionPhysics(r1, r2);
                    }
                }
            }
        }
    }

    private void applyCollisionPhysics(Ubication r1, Ubication r2) {
        double newSpeed1 = Math.max(0.0, r1.getSpeed() * COLLISION_DECELERATION_FACTOR);
        double newSpeed2 = Math.max(0.0, r2.getSpeed() * COLLISION_DECELERATION_FACTOR);

        r1.setSpeed(newSpeed1);
        r2.setSpeed(newSpeed2);

        log.info("[GameLoop] Collision resolved between runner {} and runner {} in room {}",
                r1.getRunnerId(), r2.getRunnerId(), r1.getRoomCode());
    }

    public boolean isGridLockHeld() {
        return gridLock.isLocked();
    }
}