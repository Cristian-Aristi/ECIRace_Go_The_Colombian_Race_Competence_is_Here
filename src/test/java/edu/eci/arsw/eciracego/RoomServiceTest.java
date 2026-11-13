package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomActionResult;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.service.RoomService;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RoomServiceTest {

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomServiceImpl();
    }

    @Test
    @DisplayName("Debe contener la sala CAMPUS-ECI por defecto al iniciar")
    void shouldHaveDefaultCampusRoom() {
        Optional<Room> defaultRoom = roomService.getRoom("CAMPUS-ECI");
        assertTrue(defaultRoom.isPresent(), "La sala predeterminada de campus debe existir");
        assertEquals("CAMPUS-ECI", defaultRoom.get().getRoomCode());
        assertTrue(roomService.roomExists("campus-eci"), "Debe ser insensible a mayúsculas");
    }

    @Test
    @DisplayName("Debe crear una sala con código autogenerado difícil de adivinar")
    void shouldCreateRoomWithAutoCode() {
        Room created = roomService.createRoom("Host_Daniel", null).orElseThrow();
        assertTrue(created.getRoomCode().matches("^ECI-[A-Z2-9]{6}$"), created.getRoomCode());
        assertEquals("Host_Daniel", created.getHostName());
        assertNotNull(created.getHostKey());
    }

    @Test
    @DisplayName("Sin sesión, la sala la crea el centro de control")
    void anonymousRoomIsControlCenter() {
        Room created = roomService.createRoom(null, "PC-1").orElseThrow();
        assertEquals(Room.CONTROL_CENTER_NAME, created.getHostName());
        assertNull(created.getHostUser());
    }

    @Test
    @DisplayName("Debe crear sala con código preferido normalizado a mayúsculas")
    void shouldCreateRoomWithCustomCode() {
        Room created = roomService.createRoom("Central_Control", "pista-norte").orElseThrow();
        assertEquals("PISTA-NORTE", created.getRoomCode());
    }

    @Test
    @DisplayName("Rechaza códigos de sala con caracteres no permitidos (antes se 'limpiaban')")
    void rejectsInvalidCode() {
        assertThrows(ApiException.class, () -> roomService.createRoom("H", "<script>"));
        assertTrue(roomService.getRoom("<img src=x>").isEmpty());
    }

    @Test
    @DisplayName("No debe permitir crear dos salas con el mismo código (evita sobrescritura)")
    void shouldRejectDuplicateRoomCode() {
        assertTrue(roomService.createRoom("Host_A", "DUPLICADA").isPresent());
        assertTrue(roomService.createRoom("Host_B", "duplicada").isEmpty());
        assertEquals("Host_A", roomService.getRoom("DUPLICADA").orElseThrow().getHostName());
    }

    @Test
    @DisplayName("Debe registrar y retirar corredores en la sala")
    void shouldRegisterAndRemoveRunners() {
        String code = "SALA-TEST";
        roomService.createRoom("Host", code);

        roomService.registerRunnerInRoom(code, "Daniel");
        roomService.registerRunnerInRoom(code, "Cristian");

        Room room = roomService.getRoom(code).orElseThrow();
        assertEquals(2, room.getRunnersCount());
        assertTrue(room.isMember("Daniel"));

        assertTrue(roomService.removeRunnerFromRoom(code, "Daniel"));
        assertEquals(1, room.getRunnersCount());
        assertFalse(room.isMember("Daniel"));
    }

    @Test
    @DisplayName("No debe permitir unirse a una sala llena (máximo 6 jugadores)")
    void shouldRejectJoinWhenRoomIsFull() {
        String code = "SALA-LLENA";
        roomService.createRoom("Host", code);
        for (int i = 1; i <= Room.MAX_PLAYERS; i++) {
            assertEquals(RoomJoinResult.OK, roomService.registerRunnerInRoom(code, "Piloto" + i));
        }
        assertEquals(RoomJoinResult.ROOM_FULL, roomService.registerRunnerInRoom(code, "Piloto7"));
    }

    @Test
    @DisplayName("Solo el host (o quien tiene la clave del host) puede iniciar la carrera")
    void onlyHostCanStartRace() {
        String code = "SALA-START";
        Room room = roomService.createRoom("HostOficial", code).orElseThrow();
        roomService.registerRunnerInRoom(code, "Invitado1");

        assertEquals(RoomActionResult.NOT_HOST, roomService.startRace(code, "Invitado1", null, 4.78, -74.04));
        assertEquals(RoomActionResult.NOT_HOST, roomService.startRace(code, "hostoficial", null, 4.78, -74.04),
                "La comparación del host es exacta");
        assertEquals(RoomActionResult.NOT_HOST, roomService.startRace(code, null, "clave-falsa", 4.78, -74.04));
        assertEquals(RoomActionResult.SUCCESS, roomService.startRace(code, "HostOficial", null, 4.78, -74.04));
        assertEquals(Room.RoomStatus.IN_PROGRESS, room.getStatus());
        assertEquals(RoomActionResult.ALREADY_STARTED, roomService.startRace(code, null, room.getHostKey(), 4.78, -74.04));
    }

    @Test
    @DisplayName("El centro de control inicia la carrera y mueve la meta con su clave")
    void hostKeyControlsRoom() {
        Room room = roomService.createRoom(null, "PC-SALA").orElseThrow();
        assertEquals(RoomActionResult.SUCCESS, roomService.moveMeta("PC-SALA", null, room.getHostKey(), 4.7, -74.1));
        assertEquals(4.7, room.getMetaLat());
        assertEquals(RoomActionResult.SUCCESS, roomService.startRace("PC-SALA", null, room.getHostKey(), 4.7, -74.1));
        assertEquals(RoomActionResult.ALREADY_STARTED, roomService.moveMeta("PC-SALA", null, room.getHostKey(), 1, 1));
    }

    @Test
    @DisplayName("Solo corredores y host pueden ver la sala")
    void canView() {
        Room room = roomService.createRoom("Host", "VER-SALA").orElseThrow();
        roomService.registerRunnerInRoom("VER-SALA", "Corredor");
        assertTrue(roomService.canView("VER-SALA", "Corredor", null));
        assertTrue(roomService.canView("VER-SALA", "Host", null));
        assertTrue(roomService.canView("VER-SALA", null, room.getHostKey()));
        assertFalse(roomService.canView("VER-SALA", "Extrano", null));
        assertFalse(roomService.canView("VER-SALA", null, null));
    }

    @Test
    @DisplayName("No se puede salir de la sala con la carrera iniciada")
    void cannotLeaveDuringRace() {
        Room room = roomService.createRoom("Host", "SALIR").orElseThrow();
        roomService.registerRunnerInRoom("SALIR", "Corredor");
        roomService.startRace("SALIR", "Host", null, 4.7, -74.1);
        assertFalse(roomService.removeRunnerFromRoom("SALIR", "Corredor"));
        assertTrue(room.isMember("Corredor"));
        assertTrue(roomService.isInActiveRoom("Corredor"));
    }

    @Test
    @DisplayName("La limpieza borra salas terminadas o abandonadas, nunca la sala por defecto")
    void cleanupRemovesOldRooms() {
        Room finished = roomService.createRoom("Host", "TERMINADA").orElseThrow();
        finished.startRace(4.7, -74.1);
        finished.declareWinner("x");
        roomService.createRoom("Host", "NUEVA");

        long now = System.currentTimeMillis();
        assertTrue(roomService.cleanup(now).isEmpty());

        List<String> removed = roomService.cleanup(now + 16 * 60_000L);
        assertEquals(List.of("TERMINADA"), removed);

        List<String> later = roomService.cleanup(now + 3 * 60 * 60_000L);
        assertTrue(later.contains("NUEVA"));
        assertTrue(roomService.roomExists("CAMPUS-ECI"));
    }

    @Test
    @DisplayName("Hay un máximo de salas simultáneas")
    void roomLimit() {
        RoomService limited = new RoomServiceImpl(5); // CAMPUS-ECI + 4
        for (int i = 0; i < 4; i++) {
            limited.createRoom(null, null);
        }
        assertThrows(IllegalStateException.class, () -> limited.createRoom(null, null));
    }
}
