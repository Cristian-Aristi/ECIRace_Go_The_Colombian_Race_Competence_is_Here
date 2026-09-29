package edu.eci.arsw.eciracego.model;

import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RaceManager {

    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();

    @Autowired
    private AntiCheatEngine antiCheatEngine;

    public boolean updateUbication(Ubication newUbication) {
        String id = newUbication.getRunnerId();
        Ubication previous = raceState.get(id);

        if (previous != null) {
            double speed = antiCheatEngine.calculateSpeedKmH(previous, newUbication);
            boolean suspicious = antiCheatEngine.isSuspicious(speed);

            newUbication.setSpeed(speed);
            newUbication.setSuspicious(suspicious);

            if (suspicious) {
                return false;
            }
        } else {
            newUbication.setSpeed(0.0);
            newUbication.setSuspicious(false);
        }

        raceState.put(id, newUbication);
        return true;
    }

    public Collection<Ubication> getGlobalState() {
        return raceState.values();
    }

    public Ubication getUbication(String runnerId) {
        return raceState.get(runnerId);
    }
}