package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.controller.RoomController;
import edu.eci.arsw.eciracego.dto.CreateRoomRequest;
import edu.eci.arsw.eciracego.dto.CreatedRoom;
import edu.eci.arsw.eciracego.dto.RoomSummary;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** API REST de salas (RoomController) y reglas del servicio de salas, sin levantar Spring. */
class SalasApiTest {

    private RoomServiceImpl salas;
    private RaceManager manager;
    private RoomController api;

    @BeforeEach
    void setUp() {
        salas = new RoomServiceImpl();
        manager = new RaceManager(new AntiCheatEngine(), salas, Mockito.mock(UserProfileRepository.class),
                System::currentTimeMillis);
        api = new RoomController(salas, manager, Mockito.mock(SimpMessagingTemplate.class));
    }

    private static MockHttpServletRequest como(String usuario) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.7");
        if (usuario != null) {
            r.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser(usuario));
        }
        return r;
    }

    private static MockHttpServletRequest conClave(String clave) {
        MockHttpServletRequest r = como(null);
        r.addHeader(AuthenticatedUser.HOST_KEY_HEADER, clave);
        return r;
    }

    private static Map<String, Object> cuerpo(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static int status(ResponseEntity<?> r) {
        return r.getStatusCode().value();
    }

    private static int status(Runnable accion) {
        ApiException e = assertThrows(ApiException.class, accion::run);
        return e.getStatus().value();
    }

    private String crear(String host, String codigo) {
        ResponseEntity<?> r = api.createRoom(codigo == null ? null : new CreateRoomRequest(codigo), como(host));
        assertEquals(201, status(r));
        CreatedRoom c = (CreatedRoom) r.getBody();
        assertNotNull(c.hostKey());
        return c.hostKey();
    }

    @Test
    void crearSalaConYSinSesionYCodigoRepetido() {
        crear("Ana", "PISTA");
        crear(null, null);
        assertEquals(409, status(api.createRoom(new CreateRoomRequest("pista"), como("Beto"))));
        assertEquals(400, status(() -> api.createRoom(new CreateRoomRequest("<x>"), como("Ana"))));
    }

    @Test
    void sinCupoDeSalasResponde429() {
        api = new RoomController(new RoomServiceImpl(1), manager, Mockito.mock(SimpMessagingTemplate.class));
        api.createRoom(null, como("Ana"));
        assertEquals(429, status(() -> api.createRoom(null, como("Beto"))));
    }

    @Test
    void listaPublicaSinSalasTerminadas() {
        crear("Ana", "UNO");
        crear("Beto", "DOS");
        salas.registerRunnerInRoom("DOS", "Beto");
        salas.startRace("DOS", "Beto", null, 4.8, -74.0);
        salas.getRoom("DOS").orElseThrow().declareWinner("Beto");
        List<RoomSummary> lista = api.getAllRooms();
        assertEquals(1, lista.size());
    }

    @Test
    void unirseSalirYTodosLosRechazos() {
        String clave = crear("Ana", "SALA");
        assertEquals(200, status(api.joinRoom("sala", como("Ana"))));
        assertEquals(200, status(api.joinRoom("SALA", como("Beto"))));
        assertEquals(404, status(api.joinRoom("NADA", como("Beto"))));
        assertEquals(400, status(api.joinRoom("SALA", como("   "))));
        assertEquals(401, status(() -> api.joinRoom("SALA", como(null))));

        // El host retira a Beto: ya no puede volver
        assertEquals(200, status(api.kickRunner("SALA", cuerpo("username", "Beto"), conClave(clave))));
        assertEquals(403, status(api.joinRoom("SALA", como("Beto"))));
        assertEquals(404, status(api.kickRunner("SALA", cuerpo("username", "Fantasma"), como("Ana"))));
        assertEquals(403, status(api.kickRunner("SALA", cuerpo("username", "Ana"), como("Caro"))));
        assertEquals(404, status(api.kickRunner("NADA", cuerpo("username", "Ana"), como("Ana"))));
        assertEquals(400, status(() -> api.kickRunner("SALA", null, como("Ana"))));
        assertEquals(400, status(() -> api.kickRunner("SALA", cuerpo("username", 5), como("Ana"))));
        assertEquals(400, status(() -> api.kickRunner("SALA", cuerpo("username", "a b"), como("Ana"))));

        // Sala llena
        for (int i = 1; i <= 5; i++) {
            api.joinRoom("SALA", como("Piloto" + i));
        }
        assertEquals(403, status(api.joinRoom("SALA", como("Septimo"))));

        // Salir libera el cupo; salir de una sala donde no estoy no hace nada
        assertEquals(204, status(api.leaveRoom("SALA", como("Piloto5"))));
        assertEquals(204, status(api.leaveRoom("SALA", como("Nadie"))));
        assertEquals(401, status(() -> api.leaveRoom("SALA", como(null))));

        // Con la carrera iniciada nadie más entra
        assertEquals(200, status(api.startRace("SALA", null, como("Ana"))));
        assertEquals(409, status(api.joinRoom("SALA", como("Tarde"))));
        assertEquals(409, status(api.kickRunner("SALA", cuerpo("username", "Piloto1"), como("Ana"))));
    }

    @Test
    void cambiarDeSalaLiberaElCupoAnterior() {
        crear("Ana", "PRIMERA");
        crear("Beto", "SEGUNDA");
        api.joinRoom("PRIMERA", como("Caro"));
        List<Room> salio = new ArrayList<>();
        assertEquals(RoomJoinResult.OK, salas.registerRunnerInRoom("SEGUNDA", "Caro", salio));
        assertEquals(1, salio.size());
        assertFalse(salas.getRoom("PRIMERA").orElseThrow().isMember("Caro"));
        assertTrue(salas.isInActiveRoom("Caro"));
        assertFalse(salas.isInActiveRoom("Nadie"));
        assertEquals(200, status(api.joinRoom("PRIMERA", como("Caro"))));
    }

    @Test
    void iniciarYMoverLaMetaSoloElHost() {
        String clave = crear(null, "META");
        api.joinRoom("META", como("Ana"));

        assertEquals(400, status(() -> api.moveMeta("META", null, conClave(clave))));
        assertEquals(400, status(() -> api.moveMeta("META", cuerpo("lat", "x", "lon", 1), conClave(clave))));
        assertEquals(400, status(() -> api.moveMeta("META", cuerpo("lat", 91, "lon", 1), conClave(clave))));
        assertEquals(400, status(() -> api.moveMeta("META", cuerpo("lat", 1, "lon", 200), conClave(clave))));
        assertEquals(400, status(() -> api.moveMeta("META", cuerpo("lat", true, "lon", 1), conClave(clave))));
        assertEquals(403, status(api.moveMeta("META", cuerpo("lat", 4.79, "lon", -74.05), como("Ana"))));
        assertEquals(404, status(api.moveMeta("NADA", cuerpo("lat", 4.79, "lon", -74.05), conClave(clave))));
        assertEquals(200, status(api.moveMeta("META", cuerpo("lat", " 4.79 ", "lon", -74.05), conClave(clave))));
        assertEquals(4.79, salas.getRoom("META").orElseThrow().getMetaLat(), 1e-9);

        assertEquals(403, status(api.startRace("META", cuerpo(), como("Ana"))));
        assertEquals(404, status(api.startRace("NADA", null, conClave(clave))));
        assertEquals(200, status(api.startRace("META", cuerpo("metaLat", 4.8, "metaLon", -74.04), conClave(clave))));
        assertEquals(4.8, salas.getRoom("META").orElseThrow().getMetaLat(), 1e-9);
        assertEquals(409, status(api.startRace("META", cuerpo("metaLat", 4.8), conClave(clave))));
        assertEquals(409, status(api.moveMeta("META", cuerpo("lat", 4.7, "lon", -74.0), conClave(clave))));
        assertTrue(salas.isRaceInProgress("META"));
        assertFalse(salas.isRaceInProgress("NADA"));
    }

    @Test
    void verLaSalaYLasPosicionesSoloCorredoresYHost() {
        String clave = crear("Ana", "VER");
        api.joinRoom("VER", como("Beto"));
        assertEquals("VER", api.getRoom("ver", como("Beto")).getRoomCode());
        assertEquals("VER", api.getRoom("VER", conClave(clave)).getRoomCode());
        assertEquals(401, status(() -> api.getRoom("VER", como(null))));
        assertEquals(403, status(() -> api.getRoom("VER", como("Intruso"))));
        assertEquals(404, status(() -> api.getRoom("NADA", como("Beto"))));

        api.joinRoom("VER", como("Ana"));
        api.startRace("VER", null, como("Ana"));
        Ubication u = new Ubication("Beto", 4.7810, -74.043);
        u.setRoomCode("VER");
        assertTrue(manager.updateUbication(u));
        List<Ubication> pos = api.roomPositions("VER", como("Ana"));
        assertEquals(1, pos.size());
        assertTrue(salas.canView("VER", "Beto", null));
        assertFalse(salas.canView("NADA", "Beto", null));
    }

    @Test
    void cupoDeSalasAbiertasPorCreador() {
        RoomServiceImpl conCupo = new RoomServiceImpl(100, 2, true);
        conCupo.createRoom("Ana", "SA1", "user:Ana").orElseThrow().setCreatedAt(1);
        conCupo.createRoom("Ana", "SA2", "user:Ana").orElseThrow().setCreatedAt(2);
        // La tercera cierra la sala vacía más vieja
        assertTrue(conCupo.createRoom("Ana", "SA3", "user:Ana").isPresent());
        assertFalse(conCupo.roomExists("SA1"));
        // Si todas tienen pilotos, se rechaza
        conCupo.registerRunnerInRoom("SA2", "X");
        conCupo.registerRunnerInRoom("SA3", "Y");
        ApiException e = assertThrows(ApiException.class, () -> conCupo.createRoom("Ana", "SA4", "user:Ana"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals("TOO_MANY_ROOMS", e.getCode());
        // Sin límite de tasa no hay cupo por creador
        RoomServiceImpl sinLimite = new RoomServiceImpl(100, 1, false);
        sinLimite.createRoom("Ana", "SB1", "user:Ana");
        assertTrue(sinLimite.createRoom("Ana", "SB2", "user:Ana").isPresent());
        assertTrue(sinLimite.createRoom(null, "SB3", null).isPresent());
    }

    @Test
    void limpiezaDeSalasInactivas() {
        Room esperando = salas.createRoom("Ana", "ESPERA").orElseThrow();
        Room corriendo = salas.createRoom("Beto", "CORRE").orElseThrow();
        Room terminada = salas.createRoom("Caro", "FIN").orElseThrow();
        corriendo.startRace(4.8, -74.0);
        terminada.startRace(4.8, -74.0);
        terminada.finishRace();
        assertFalse(terminada.finishRace());
        long ahora = System.currentTimeMillis();
        assertTrue(salas.cleanup(ahora).isEmpty());
        assertEquals(List.of("FIN"), salas.cleanup(ahora + 16 * 60_000L));
        assertEquals(List.of("CORRE"), salas.cleanup(ahora + 61 * 60_000L));
        assertEquals(List.of("ESPERA"), salas.cleanup(ahora + 3 * 60 * 60_000L));
        assertFalse(salas.removeRunnerFromRoom("ESPERA", "Ana"));
        assertFalse(salas.removeRunnerFromRoom("X", null));
        assertTrue(esperando.getRoomCode().equals("ESPERA"));
    }

    @Test
    void reglasDeLaSala() {
        Room r = new Room();
        assertEquals(Room.CONTROL_CENTER_NAME, r.getHostName());
        r.setRoomCode("R1");
        r.setCreatedAt(5);
        r.setStatus(Room.RoomStatus.WAITING);
        r.setRanked(Boolean.FALSE);
        assertEquals(5, r.getCreatedAt());
        assertFalse(r.getRanked());
        assertFalse(r.checkHostKey(null));
        assertFalse(r.checkHostKey(""));
        assertTrue(r.checkHostKey(r.getHostKey()));
        assertFalse(r.isMember(null));
        assertFalse(r.removeRunner(null));
        assertFalse(r.removeRunner("Nadie"));
        assertEquals(RoomJoinResult.INVALID_NAME, r.addRunner(null));
        assertEquals(RoomJoinResult.OK, r.addRunner("Ana"));
        assertEquals(RoomJoinResult.OK, r.addRunner(" Ana "));
        assertEquals(RoomActionResult.RUNNER_NOT_FOUND, r.kick(null));
        assertFalse(r.declareWinner("Ana"));
        assertTrue(r.startRace(4.8, -74.0));
        assertFalse(r.removeRunner("Ana"));
        assertFalse(r.startRace(4.8, -74.0));
        assertTrue(r.finishRace());
        assertTrue(r.getFinishedAt() > 0);
        assertTrue(r.getLastActivity() > 0);
        assertEquals(15.0, r.getRadioMetaMetros());
        assertNull(r.getCreatorKey());
    }
}
