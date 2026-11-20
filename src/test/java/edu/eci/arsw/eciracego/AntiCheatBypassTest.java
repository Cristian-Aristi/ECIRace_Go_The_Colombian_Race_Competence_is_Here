package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.GameLoopService;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Las trampas de las auditorías ya no funcionan. */
class AntiCheatBypassTest {

    /** Meta lejos de la salida (unos 2,4 km al norte del campus). */
    private static final double META_LAT = 4.8050;
    private static final double META_LON = -74.0430;

    private final AtomicLong serverClock = new AtomicLong(1_000_000_000L);
    private RoomServiceImpl rooms;
    private RaceManager manager;

    @BeforeEach
    void setUp() {
        rooms = new RoomServiceImpl();
        rooms.createRoom("Host", "SALA-1");
        rooms.registerRunnerInRoom("SALA-1", "Tramposo");
        rooms.startRace("SALA-1", "Host", null, META_LAT, META_LON);
        manager = new RaceManager(new AntiCheatEngine(), rooms, Mockito.mock(UserProfileRepository.class), serverClock::get);
    }

    private static Ubication at(String room, double lat, double lon, long clientTs) {
        Ubication u = new Ubication("Tramposo", lat, lon);
        u.setRoomCode(room);
        u.setTimestamp(clientTs);
        return u;
    }

    private static Ubication at(double lat, double lon, long clientTs) {
        return at("SALA-1", lat, lon, clientTs);
    }

    private double latitude() {
        return manager.getUbication("SALA-1", "Tramposo").getLatitude();
    }

    @Test
    void nanAndOutOfRangeCoordinatesAreRejected() {
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, 1000)));
        assertFalse(manager.updateUbication(at(Double.NaN, -74.0430, 2000)));
        assertFalse(manager.updateUbication(at(4.7828, Double.POSITIVE_INFINITY, 3000)));
        assertFalse(manager.updateUbication(at(95.0, -74.0430, 4000)));
        assertEquals(4.7828, latitude());
    }

    @Test
    void backdatedTimestampNoLongerResetsTheReference() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, t)));
        serverClock.addAndGet(1000);
        // Antes: un timestamp 5 s en el pasado = "reloj atrasado" -> nueva referencia SIN validar velocidad
        assertFalse(manager.updateUbication(at(4.7928, -74.0430, t - 6000)), "Salto de ~1 km aceptado");
        assertEquals(4.7828, latitude());
    }

    @Test
    void inflatedTimestampIsBoundedByRealServerTime() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.7828, -74.0430, t)));
        serverClock.addAndGet(1000); // en el servidor solo pasó 1 s
        // El teléfono dice que pasó 1 hora: ~1,1 km "a 1 km/h". Con el tope del servidor son ~4 s -> ~1000 km/h
        assertFalse(manager.updateUbication(at(4.7928, -74.0430, t + 3_600_000)));
        assertEquals(4.7828, latitude());
    }

    @Test
    void clockToleranceIsNotGrantedOnEveryPacket() {
        // Auditoría, 2.ª ronda: adelantar el timestamp 3 s en cada paquete (enviado cada 200 ms) daba
        // 3 s "gratis" por paquete: ~24 m cada 200 ms, más de 400 km/h.
        long t = 10_000_000L;
        double lat = 4.7828;
        assertTrue(manager.updateUbication(at(lat, -74.0430, t)));
        for (int i = 1; i <= 20; i++) {
            serverClock.addAndGet(200);
            manager.updateUbication(at(lat + i * 0.000216, -74.0430, t + i * 3000L)); // ~24 m por paquete
        }
        // En 4 s reales (+3 s de tolerancia) a 30 km/h se recorren como mucho ~58 m (~0.00053°)
        assertTrue(latitude() - 4.7828 < 0.00055, "Avanzó " + (latitude() - 4.7828) + "°");
    }

    @Test
    void normalRunningIsStillAccepted() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.782800, -74.043000, t)));
        serverClock.addAndGet(2000);
        // ~11 m en 2 s = ~20 km/h
        assertTrue(manager.updateUbication(at(4.782900, -74.043000, t + 2000)));
        assertEquals(4.7829, latitude(), 1e-9);
    }

    @Test
    void burstOfBufferedFixesUsesTheGpsInterval() {
        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at(4.782800, -74.043000, t)));
        serverClock.addAndGet(50); // llegaron casi juntos (red móvil), pero el GPS los tomó con 2 s de diferencia
        assertTrue(manager.updateUbication(at(4.782900, -74.043000, t + 2000)));
    }

    @Test
    void firstPacketInsideTheFinishCannotWin() {
        // Auditoría, 2.ª ronda: el primer paquete se aceptaba en cualquier lugar -> victoria instantánea
        assertFalse(manager.updateUbication(at(META_LAT, META_LON, 1000)));
        assertFalse(manager.updateUbication(at(META_LAT - 0.0002, META_LON, 2000)), "A ~22 m de la meta tampoco");
        assertNull(manager.getUbication("SALA-1", "Tramposo"));

        GameLoopService loop = new GameLoopService(manager, Mockito.mock(SimpMessagingTemplate.class), new AntiCheatEngine());
        loop.processGameTick();
        Room room = rooms.getRoom("SALA-1").orElseThrow();
        assertEquals(Room.RoomStatus.IN_PROGRESS, room.getStatus());
        assertNull(room.getWinnerId());
    }

    @Test
    void firstPacketFarFromTheFinishIsAccepted() {
        assertTrue(manager.updateUbication(at(META_LAT - 0.0005, META_LON, 1000)), "A ~55 m de la meta se puede salir");
    }

    @Test
    void telemetryOnlyCountsForRunnersOfARaceInProgress() {
        rooms.createRoom("Otro", "SALA-ESPERA");
        rooms.registerRunnerInRoom("SALA-ESPERA", "Tramposo");
        assertFalse(manager.updateUbication(at("SALA-ESPERA", 4.7828, -74.0430, 1000)), "Sala sin iniciar");
        assertFalse(manager.updateUbication(at("NO-EXISTE", 4.7828, -74.0430, 1000)), "Sala inexistente");

        Ubication stranger = at(4.7828, -74.0430, 1000);
        stranger.setRunnerId("Colado");
        assertFalse(manager.updateUbication(stranger), "No inscrito en la sala");
    }

    @Test
    void switchingRoomsDoesNotResetTheReference() {
        // Antes: alternar el roomCode entre dos salas en curso reiniciaba la referencia en cada paquete
        rooms.createRoom("Yo", "SALA-2");
        rooms.registerRunnerInRoom("SALA-2", "Tramposo");
        rooms.startRace("SALA-2", "Yo", null, 4.70, -74.10);

        long t = 10_000_000L;
        assertTrue(manager.updateUbication(at("SALA-1", 4.7828, -74.0430, t)));
        serverClock.addAndGet(1000);
        assertTrue(manager.updateUbication(at("SALA-2", 4.7828, -74.0430, t + 1000)));
        serverClock.addAndGet(1000);
        // Salto de ~2 km en la SALA-1: su recorrido sigue ahí y lo rechaza
        assertFalse(manager.updateUbication(at("SALA-1", 4.8000, -74.0430, t + 2000)));
        assertEquals(4.7828, latitude());
    }

    @Test
    void clientCannotInjectArbitraryCarText() {
        Ubication u = at(4.78, -74.04, 1);
        u.setCar("<img src=x onerror=alert(1)>");
        assertEquals("RENAULT_4", u.getCar());
        u.setCar("mazda_323");
        assertEquals("MAZDA_323", u.getCar());
    }

    @Test
    void stalePositionsAreKeptDuringTheRaceAndCleanedAfterwards() {
        assertTrue(manager.updateUbication(at(4.78, -74.04, 1)));
        long later = serverClock.get() + 11 * 60_000L;
        // Con la carrera en curso no se borra: el siguiente paquete no puede volver a ser "el primero"
        assertEquals(0, manager.cleanup(java.util.List.of(), later));

        rooms.getRoom("SALA-1").orElseThrow().finishRace();
        assertEquals(1, manager.cleanup(java.util.List.of(), later));
        assertTrue(manager.getGlobalState().isEmpty());
    }

    @Test
    void positionsOfRemovedRoomsAreCleaned() {
        assertTrue(manager.updateUbication(at(4.78, -74.04, 1)));
        assertEquals(1, manager.cleanup(java.util.List.of("SALA-1"), serverClock.get()));
    }
}
