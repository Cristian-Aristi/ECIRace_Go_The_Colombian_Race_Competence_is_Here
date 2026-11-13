package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Las trampas de la auditoría ya no funcionan. */
class AntiCheatBypassTest {

    private final AtomicLong serverClock = new AtomicLong(1_000_000_000L);
    private RaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new RaceManager(new AntiCheatEngine(), new RoomServiceImpl(),
                Mockito.mock(UserProfileRepository.class), serverClock::get);
    }

    private static Ubication at(double lat, double lon, long clientTs) {
        Ubication u = new Ubication("Tramposo", lat, lon);
        u.setRoomCode("SALA-1");
        u.setTimestamp(clientTs);
        return u;
    }

    @Test
    void nanAndOutOfRangeCoordinatesAreRejected() {
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, 1000)));
        assertFalse(manager.updateUbication(at(Double.NaN, -74.0430, 2000)));
        assertFalse(manager.updateUbication(at(4.7828, Double.POSITIVE_INFINITY, 3000)));
        assertFalse(manager.updateUbication(at(95.0, -74.0430, 4000)));
        assertEquals(4.7828, manager.getUbication("Tramposo").getLatitude());
    }

    @Test
    void backdatedTimestampNoLongerResetsTheReference() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, t)));
        serverClock.addAndGet(1000);
        // Antes: un timestamp 5 s en el pasado = "reloj atrasado" -> nueva referencia SIN validar velocidad
        assertFalse(manager.updateUbication(at(4.7928, -74.0430, t - 6000)), "Salto de ~1 km aceptado");
        assertEquals(4.7828, manager.getUbication("Tramposo").getLatitude());
    }

    @Test
    void inflatedTimestampIsBoundedByRealServerTime() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, t)));
        serverClock.addAndGet(1000); // en el servidor solo pasó 1 s
        // El teléfono dice que pasó 1 hora: ~1,1 km "a 1 km/h". Con el tope del servidor son ~4 s -> ~1000 km/h
        assertFalse(manager.updateUbication(at(4.7928, -74.0430, t + 3_600_000)));
        assertEquals(4.7828, manager.getUbication("Tramposo").getLatitude());
    }

    @Test
    void normalRunningIsStillAccepted() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.782800, -74.043000, t)));
        serverClock.addAndGet(2000);
        // ~11 m en 2 s = ~20 km/h
        assertTrue(manager.updateUbication(at(4.782900, -74.043000, t + 2000)));
        assertEquals(4.7829, manager.getUbication("Tramposo").getLatitude(), 1e-9);
    }

    @Test
    void burstOfBufferedFixesUsesTheGpsInterval() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.782800, -74.043000, t)));
        serverClock.addAndGet(50); // llegaron casi juntos (red móvil), pero el GPS los tomó con 2 s de diferencia
        assertTrue(manager.updateUbication(at(4.782900, -74.043000, t + 2000)));
    }

    @Test
    void clientCannotInjectArbitraryCarText() {
        Ubication u = at(4.78, -74.04, 1);
        u.setCar("<img src=x onerror=alert(1)>");
        assertEquals("RENAULT_4", u.getCar());
        u.setCar("mazda_323");
        assertEquals("MAZDA_323", u.getCar());
    }
}
