package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.controller.RaceController;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.model.VehicleType;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.GameLoopService;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Una carrera completa de punta a punta: telemetría por WebSocket, anti-trampas, bucle de juego,
 * llegada a la meta y resultados. Usa los servicios reales con un reloj controlado.
 */
class CarreraEnCursoTest {

    private static final double META_LAT = 4.8050;
    private static final double META_LON = -74.0430;
    /** ~200 m al sur de la meta. */
    private static final double SALIDA_LAT = META_LAT - 0.0018;

    private final AtomicLong reloj = new AtomicLong(1_000_000L);
    private RoomServiceImpl salas;
    private RaceManager manager;
    private UserProfileRepository repo;
    private SimpMessagingTemplate mensajes;
    private GameLoopService loop;
    private Room sala;

    @BeforeEach
    void setUp() {
        salas = new RoomServiceImpl();
        sala = salas.createRoom("Ana", "CARRERA").orElseThrow();
        salas.registerRunnerInRoom("CARRERA", "Ana");
        salas.registerRunnerInRoom("CARRERA", "Beto");
        repo = Mockito.mock(UserProfileRepository.class);
        manager = new RaceManager(new AntiCheatEngine(), salas, repo, reloj::get);
        mensajes = Mockito.mock(SimpMessagingTemplate.class);
        loop = new GameLoopService(manager, mensajes, new AntiCheatEngine());
    }

    private Ubication punto(String piloto, double lat, long ts) {
        Ubication u = new Ubication(piloto, lat, META_LON);
        u.setRoomCode("carrera");
        u.setTimestamp(ts);
        return u;
    }

    private void iniciar() {
        assertEquals(edu.eci.arsw.eciracego.model.RoomActionResult.SUCCESS,
                salas.startRace("CARRERA", "Ana", null, META_LAT, META_LON));
    }

    @Test
    void sinPosicionesElBucleNoHaceNada() {
        loop.processGameTick();
        assertFalse(loop.isGridLockHeld());
        GameLoopService vacio = new GameLoopService(Mockito.mock(RaceManager.class), mensajes, new AntiCheatEngine());
        vacio.processGameTick();
        verify(mensajes, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void elPrimeroEnLlegarALaMetaGanaYSeAvisaALaSala() {
        iniciar();
        assertTrue(manager.updateUbication(punto("Ana", SALIDA_LAT, reloj.get())));
        assertTrue(manager.updateUbication(punto("Beto", SALIDA_LAT, reloj.get())));
        loop.processGameTick();
        assertEquals(Room.RoomStatus.IN_PROGRESS, sala.getStatus());

        // Ana trota hasta la meta (~11 km/h): 20 pasos de 10 m cada 3,3 s
        long t = reloj.get();
        for (int i = 1; i <= 20; i++) {
            t += 3_300;
            reloj.set(t);
            double lat = SALIDA_LAT + (META_LAT - SALIDA_LAT) * i / 20.0;
            assertTrue(manager.updateUbication(punto("Ana", lat, t)), "paso " + i);
        }
        loop.processGameTick();

        assertEquals(Room.RoomStatus.FINISHED, sala.getStatus());
        assertEquals("Ana", sala.getWinnerId());
        verify(mensajes).convertAndSend(eq("/topic/rooms/CARRERA/status"), any(Object.class));
        // Una segunda vuelta del bucle no vuelve a declarar ganador
        loop.processGameTick();
        verify(mensajes).convertAndSend(eq("/topic/rooms/CARRERA/status"), any(Object.class));
    }

    @Test
    void siFallaGuardarResultadosLaCarreraIgualTermina() {
        RaceManager roto = Mockito.mock(RaceManager.class);
        Room enCurso = new Room("ROTA", "Ana");
        enCurso.addRunner("Ana");
        enCurso.startRace(META_LAT, META_LON);
        Ubication enLaMeta = new Ubication("Ana", META_LAT, META_LON);
        enLaMeta.setRoomCode("ROTA");
        Ubication sinSala = new Ubication("Nadie", META_LAT, META_LON);
        Mockito.when(roto.getGlobalState()).thenReturn(List.of(enLaMeta, sinSala));
        Mockito.when(roto.getRoom("ROTA")).thenReturn(enCurso);
        Mockito.doThrow(new IllegalStateException("BD caída")).when(roto).saveResults(enCurso);

        new GameLoopService(roto, mensajes, new AntiCheatEngine()).processGameTick();

        assertEquals("Ana", enCurso.getWinnerId());
        verify(mensajes).convertAndSend(eq("/topic/rooms/ROTA/status"), any(Object.class));
    }

    @Test
    void lejosDeLaMetaOSinCarreraNoHayGanador() {
        RaceManager mock = Mockito.mock(RaceManager.class);
        Room esperando = new Room("ESPERA", "Ana");
        Room enCurso = new Room("LEJOS", "Ana");
        enCurso.startRace(META_LAT, META_LON);
        Ubication a = new Ubication("Ana", META_LAT, META_LON);
        a.setRoomCode("ESPERA");
        Ubication b = new Ubication("Beto", SALIDA_LAT, META_LON);
        b.setRoomCode("LEJOS");
        Ubication c = new Ubication("Caro", META_LAT, META_LON);
        c.setRoomCode("NO-EXISTE");
        Mockito.when(mock.getGlobalState()).thenReturn(List.of(a, b, c));
        Mockito.when(mock.getRoom("ESPERA")).thenReturn(esperando);
        Mockito.when(mock.getRoom("LEJOS")).thenReturn(enCurso);

        new GameLoopService(mock, mensajes, new AntiCheatEngine()).processGameTick();

        assertNull(enCurso.getWinnerId());
        assertEquals(Room.RoomStatus.WAITING, esperando.getStatus());
    }

    @Test
    void colisionesSoloEntreCorredoresDeLaMismaSala() {
        Ubication a = new Ubication("A", META_LAT, META_LON);
        a.setRoomCode("UNO");
        a.setSpeed(10);
        Ubication b = new Ubication("B", META_LAT, META_LON);
        b.setRoomCode("DOS");
        b.setSpeed(10);
        Ubication sinSala = new Ubication("C", META_LAT, META_LON);
        sinSala.setSpeed(10);
        loop.resolveCollisions(List.of(a));
        loop.resolveCollisions(List.of(a, b));
        loop.resolveCollisions(List.of(sinSala, a));
        assertEquals(10, a.getSpeed());
        assertEquals(10, b.getSpeed());
        assertEquals(0.0, loop.calculateDistanceMeters(META_LAT, META_LON, META_LAT, META_LON), 1e-9);
        assertEquals(200, loop.calculateDistanceMeters(SALIDA_LAT, META_LON, META_LAT, META_LON), 1.0);
    }

    @Test
    void elAntiTrampasDescartaSaltosRepetidosYRelojesAdelantados() {
        iniciar();
        long t0 = reloj.get();
        // Salida pegada a la meta: rechazada
        assertFalse(manager.updateUbication(punto("Ana", META_LAT, t0)));
        assertTrue(manager.updateUbication(punto("Ana", SALIDA_LAT, t0)));
        // Mismo timestamp: duplicado
        assertFalse(manager.updateUbication(punto("Ana", SALIDA_LAT, t0)));
        // Reloj del teléfono muy adelantado sin que pase tiempo real
        reloj.addAndGet(1);
        Ubication adelantado = punto("Ana", SALIDA_LAT + 0.00001, t0 + 60_000);
        assertTrue(manager.updateUbication(adelantado));
        assertFalse(manager.updateUbication(punto("Ana", SALIDA_LAT + 0.00002, t0 + 120_000)),
                "Hasta que no pase tiempo real en el servidor no avanza");
        // Teletransporte: sospechoso, se conserva la última posición válida
        reloj.addAndGet(10_000);
        Ubication salto = punto("Ana", META_LAT, t0 + 130_000);
        assertFalse(manager.updateUbication(salto));
        assertTrue(salto.isSuspicious());
        assertEquals(SALIDA_LAT + 0.00001, manager.getUbication("carrera", "Ana").getLatitude(), 1e-9);
        // Timestamp vacío: se usa la hora del servidor
        Ubication sinHora = punto("Beto", SALIDA_LAT, 0);
        assertTrue(manager.updateUbication(sinHora));
        assertEquals(reloj.get(), sinHora.getTimestamp());
    }

    @Test
    void rechazaTelemetriaInvalidaOFueraDeLaCarrera() {
        assertFalse(manager.updateUbication(null));
        assertFalse(manager.updateUbication(new Ubication(null, 1, 1)));
        assertFalse(manager.updateUbication(punto("Ana", 95.0, 1)));
        Ubication otraSala = punto("Ana", SALIDA_LAT, 1);
        otraSala.setRoomCode("NO-EXISTE");
        assertFalse(manager.updateUbication(otraSala));
        assertFalse(manager.updateUbication(punto("Ana", SALIDA_LAT, 1)), "La carrera no ha empezado");
        iniciar();
        assertFalse(manager.updateUbication(punto("Intruso", SALIDA_LAT, 1)));
        assertNull(manager.getUbication(null, "Ana"));
        assertNull(manager.getUbication("CARRERA", null));
        assertNull(manager.getRoom(null));
        assertSame(sala, manager.getRoom("carrera"));
    }

    @Test
    void limpiezaDePosicionesViejasYDeSalasCerradas() {
        iniciar();
        manager.updateUbication(punto("Ana", SALIDA_LAT, reloj.get()));
        assertEquals(0, manager.cleanup(List.of(), reloj.get() + 60 * 60_000L), "La carrera sigue en curso");
        assertEquals(1, manager.cleanup(List.of("carrera"), reloj.get()));

        Room otra = salas.createRoom("Caro", "OTRA").orElseThrow();
        salas.registerRunnerInRoom("OTRA", "Caro");
        salas.startRace("OTRA", "Caro", null, META_LAT, META_LON);
        Ubication u = punto("Caro", SALIDA_LAT, reloj.get());
        u.setRoomCode("OTRA");
        assertTrue(manager.updateUbication(u));
        otra.declareWinner("Caro");
        assertEquals(0, manager.cleanup(List.of(), reloj.get() + 1_000));
        assertEquals(1, manager.cleanup(List.of(), reloj.get() + 11 * 60_000L));

        manager.updateUbication(punto("Beto", SALIDA_LAT, reloj.get()));
        salas.cleanup(System.currentTimeMillis() + 3 * 60 * 60_000L);
        assertEquals(1, manager.cleanup(List.of(), reloj.get()), "La sala ya no existe");
    }

    @Test
    void resultadosSoloParaCarrerasConGanador() {
        manager.saveResults(sala);
        assertNull(sala.getRanked());
        Room sinLargada = new Room("X", "Ana");
        sinLargada.addRunner("Ana");
        assertFalse(manager.isRanked(sinLargada));
        sinLargada.startRace(META_LAT, META_LON);
        sinLargada.declareWinner("Ana");
        sinLargada.setStartedAt(0);
        assertFalse(manager.isRanked(sinLargada));
    }

    @Test
    void carreraQueCuentaSumaVictoriaSoloAQuienTienePerfil() {
        iniciar();
        UserProfile ana = new UserProfile("Ana", null, "Ana", "");
        Mockito.when(repo.findById("Ana")).thenReturn(Optional.of(ana));
        Mockito.when(repo.findById("Beto")).thenReturn(Optional.empty());
        manager.updateUbication(punto("Ana", SALIDA_LAT, reloj.get()));
        manager.updateUbication(punto("Beto", SALIDA_LAT, reloj.get()));
        sala.declareWinner("Ana");
        sala.setStartedAt(System.currentTimeMillis() - 120_000);
        manager.saveResults(sala);
        assertEquals(Boolean.TRUE, sala.getRanked());
        assertEquals(1, ana.getWinsCount());
    }

    @Test
    void telemetriaPorWebSocketUsaElPilotoDeLaSesion() {
        RaceController controller = new RaceController(manager, salas, Runnable::run, mensajes);
        Ubication u = punto("Suplantado", SALIDA_LAT, reloj.get());
        controller.receiveCoordinate(null, new AuthenticatedUser("Ana"));
        controller.receiveCoordinate(u, null);
        controller.receiveCoordinate(u, new AuthenticatedUser("Ana"));     // la carrera no ha empezado
        verify(mensajes, never()).convertAndSend(anyString(), any(Object.class));

        iniciar();
        Ubication intruso = punto("x", SALIDA_LAT, reloj.get());
        controller.receiveCoordinate(intruso, new AuthenticatedUser("Intruso"));
        Ubication cerca = punto("x", META_LAT, reloj.get());
        controller.receiveCoordinate(cerca, new AuthenticatedUser("Beto")); // salida junto a la meta: descartada
        verify(mensajes, never()).convertAndSend(anyString(), any(Object.class));

        Ubication valida = punto("x", SALIDA_LAT, reloj.get());
        controller.receiveCoordinate(valida, new AuthenticatedUser("Ana"));
        assertEquals("Ana", valida.getRunnerId());
        verify(mensajes).convertAndSend(eq("/topic/rooms/CARRERA"), any(Object.class));
    }

    @Test
    void modeloDeUbicacionYVehiculos() {
        Ubication u = new Ubication();
        assertEquals("RENAULT_4", u.getCar());
        u.setCar("mazda_323");
        assertEquals(VehicleType.MAZDA_323, u.getVehicleType());
        u.setCar("tanque");
        u.setCar("   ");
        u.setCar(null);
        u.setCar("X".repeat(40));
        assertEquals("MAZDA_323", u.getCar());
        u.setVehicleType(null);
        u.setVehicleType(VehicleType.CHEVROLET_SPARK);
        assertEquals("CHEVROLET_SPARK", u.getCar());
        u.setRoomCode("  ");
        assertNull(u.getRoomCode());
        u.setRoomCode(null);
        u.setLatitude(1.5);
        u.setLongitude(2.5);
        u.setRunnerId("Ana");
        u.setReceivedAt(7);
        u.setSuspicious(true);
        assertEquals(1.5, u.getLatitude());
        assertEquals(2.5, u.getLongitude());
        assertEquals(7, u.getReceivedAt());
        assertTrue(u.isSuspicious());
        for (VehicleType v : VehicleType.values()) {
            assertNotNull(v.getDisplayName());
            assertTrue(v.getMaxSpeedKmH() > 0);
            assertTrue(v.getColorHex().startsWith("#"));
        }
    }

    @Test
    void modosDelAntiTrampas() {
        AntiCheatEngine motor = new AntiCheatEngine();
        assertEquals(AntiCheatEngine.RaceMode.RUNNING, motor.getRaceMode());
        motor.setRaceMode(AntiCheatEngine.RaceMode.WALKING);
        assertTrue(motor.isSuspicious(20));
        assertTrue(motor.isSuspicious(Double.NaN));
        assertTrue(Double.isNaN(motor.speedKmH(Double.NaN, 1000)));
        assertTrue(Double.isNaN(motor.speedKmH(10, 0)));
        assertEquals(0.0, motor.calculateSpeedKmH(null, new Ubication()));
        Ubication a = new Ubication("A", META_LAT, META_LON);
        Ubication b = new Ubication("A", SALIDA_LAT, META_LON);
        b.setTimestamp(a.getTimestamp());
        assertTrue(motor.calculateSpeedKmH(a, b) > 0, "Sin tiempo entre lecturas se asume 1,5 s");
    }
}
