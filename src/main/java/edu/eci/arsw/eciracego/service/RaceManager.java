package edu.eci.arsw.eciracego.service;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;

import edu.eci.arsw.eciracego.repository.UserProfileRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RaceManager {

    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Room> activeRooms = new ConcurrentHashMap<>();

    @Autowired
    private AntiCheatEngine antiCheatEngine;

    @Autowired
    private UserProfileRepository userProfileRepository;

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

    public Room getRoom(String roomCode) {
        return this.activeRooms.get(roomCode);
    }

    @Transactional
    public void saveResults(Room room) {
        if (room.getWinnerId() == null) {
            return;
        }

        for (String runnerId : room.getActiveRunners()) {
            userProfileRepository.findById(runnerId).ifPresent(perfil -> {

                perfil.setRacesCount(perfil.getRacesCount() + 1);

                if (runnerId.equalsIgnoreCase(room.getWinnerId())) {
                    perfil.setWinsCount(perfil.getWinsCount() + 1);
                }

                userProfileRepository.save(perfil);
            });
        }

        System.out.println("💾 Resultados guardados en BD. Ganador indiscutible: " + room.getWinnerId());
    }
}