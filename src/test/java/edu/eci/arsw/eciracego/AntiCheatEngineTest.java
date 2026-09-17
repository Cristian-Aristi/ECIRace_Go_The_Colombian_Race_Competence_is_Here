package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AntiCheatEngineTest {

    private final AntiCheatEngine antiCheatEngine = new AntiCheatEngine();

    @Test
    void shouldCalculateAccurateDistanceInCampus() {
        //campus ECI (~200 metros)
        double lat1 = 4.782800, lon1 = -74.043000;
        double lat2 = 4.784500, lon2 = -74.043000;

        double distance = antiCheatEngine.calculateDistanceMeters(lat1, lon1, lat2, lon2);
        assertTrue(distance > 180 && distance < 250, "La distancia debe estar entre 180 y 250 metros");
    }

    @Test
    void shouldDetectTeleportationOrExcessiveSpeed() {
        long now = System.currentTimeMillis();
        Ubication p1 = new Ubication("runner1", 4.782800, -74.043000);
        p1.setTimestamp(now);

        // Salto de 3 kilómetros en 1 segundo
        Ubication p2 = new Ubication("runner1", 4.812800, -74.043000);
        p2.setTimestamp(now + 1000);

        double speed = antiCheatEngine.calculateSpeedKmH(p1, p2);
        assertTrue(speed > 500, "Velocidad anómala");
        assertTrue(antiCheatEngine.isSuspicious(speed), "Debe marcar sospecha de trampa/teletransporte");
    }

    @Test
    void shouldAcceptNormalRunningSpeed() {
        long now = System.currentTimeMillis();
        Ubication p1 = new Ubication("runner1", 4.782800, -74.043000);
        p1.setTimestamp(now);

        // (~12 km/h)
        Ubication p2 = new Ubication("runner1", 4.782850, -74.043000);
        p2.setTimestamp(now + 1500);

        double speed = antiCheatEngine.calculateSpeedKmH(p1, p2);
        assertFalse(antiCheatEngine.isSuspicious(speed), "Una velocidad humana de trote no debe ser trampa");
    }
}