package edu.eci.arsw.eciracego.model;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collection;

@Service
public class RaceManager {

    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();

    public void updateUbication(Ubication ubication) {
        raceState.put(ubication.getRunnerId(), ubication);
    }

    public Collection<Ubication> getGlobalState() {
        return raceState.values();
    }
}
