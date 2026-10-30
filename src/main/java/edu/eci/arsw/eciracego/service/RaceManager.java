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

    /** Si llega un paquete más viejo que esto respecto al anterior, se considera desordenado y se descarta. */
    private static final long MAX_REORDER_WINDOW_MS = 5_000;

    private final ConcurrentHashMap<String, Ubication> raceState = new ConcurrentHashMap<>();

    @Autowired
    private AntiCheatEngine antiCheatEngine;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    /**
     * Actualiza la posición de un corredor de forma ATÓMICA.
     * <p>
     * Los paquetes de telemetría de varios dispositivos (y varios paquetes del mismo
     * dispositivo) se procesan en paralelo en el pool RaceWorker-X. Antes se hacía
     * get() + calcular + put(), así que dos hilos podían leer el mismo "anterior" y
     * pisarse, o un paquete viejo podía sobrescribir a uno más nuevo. Con compute()
     * el cálculo y la escritura de UNA misma clave se ejecutan bajo el mismo candado
     * interno del mapa, sin bloquear a los demás corredores.
     *
     * @return true si el paquete fue aceptado y debe retransmitirse; false si se
     *         descartó (paquete duplicado/desordenado o velocidad sospechosa).
     */
    public boolean updateUbication(Ubication newUbication) {
        String id = newUbication.getRunnerId();
        boolean[] accepted = {false};

        raceState.compute(id, (key, previous) -> {
            // Primer paquete, o el corredor viene de otra sala: no hay referencia válida
            if (previous == null || !sameRoom(previous, newUbication)) {
                newUbication.setSpeed(0.0);
                newUbication.setSuspicious(false);
                accepted[0] = true;
                return newUbication;
            }

            long delta = newUbication.getTimestamp() - previous.getTimestamp();
            // Duplicado (mismo fix GPS reenviado) o llegó desordenado: se ignora sin marcarlo como trampa
            if (delta == 0 || (delta < 0 && delta > -MAX_REORDER_WINDOW_MS)) {
                return previous;
            }
            // Reloj muy atrasado (otro dispositivo / reloj reiniciado): nueva referencia sin validar velocidad
            if (delta <= -MAX_REORDER_WINDOW_MS) {
                newUbication.setSpeed(0.0);
                newUbication.setSuspicious(false);
                accepted[0] = true;
                return newUbication;
            }

            double speed = antiCheatEngine.calculateSpeedKmH(previous, newUbication);
            boolean suspicious = antiCheatEngine.isSuspicious(speed);
            newUbication.setSpeed(speed);
            newUbication.setSuspicious(suspicious);

            if (suspicious) {
                return previous; // se conserva la última posición válida
            }
            accepted[0] = true;
            return newUbication;
        });

        return accepted[0];
    }

    private boolean sameRoom(Ubication a, Ubication b) {
        return a.getRoomCode() != null && a.getRoomCode().equalsIgnoreCase(b.getRoomCode());
    }

    public Collection<Ubication> getGlobalState() {
        return raceState.values();
    }

    public Ubication getUbication(String runnerId) {
        return raceState.get(runnerId);
    }

    /**
     * Antes leía un mapa interno (activeRooms) que nunca se llenaba, por eso devolvía
     * siempre null y el GameLoop jamás detectaba la meta. Ahora delega en RoomService,
     * que es la fuente única de las salas.
     */
    public Room getRoom(String roomCode) {
        if (roomCode == null) {
            return null;
        }
        return roomService.getRoom(roomCode).orElse(null);
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