package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.RoomJoinResult;
import edu.eci.arsw.eciracego.service.RoomService;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Debe crear una nueva sala con código autogenerado")
    void shouldCreateRoomWithAutoCode() {
        Optional<Room> created = roomService.createRoom("Host_Daniel", null);
        assertTrue(created.isPresent(), "La sala debió crearse exitosamente");
        assertNotNull(created.get().getRoomCode());
        assertTrue(created.get().getRoomCode().startsWith("ECI-"));
        assertEquals("Host_Daniel", created.get().getHostName());
        assertTrue(roomService.roomExists(created.get().getRoomCode()));
    }

    @Test
    @DisplayName("Debe crear sala con código preferido normalizado a mayúsculas")
    void shouldCreateRoomWithCustomCode() {
        Optional<Room> created = roomService.createRoom("Central_Control", "pista-norte");
        assertTrue(created.isPresent(), "La sala debió crearse exitosamente");
        assertEquals("PISTA-NORTE", created.get().getRoomCode());
        assertEquals("Central_Control", created.get().getHostName());
    }

    @Test
    @DisplayName("No debe permitir crear dos salas con el mismo código (evita sobrescritura)")
    void shouldRejectDuplicateRoomCode() {
        Optional<Room> first = roomService.createRoom("Host_A", "DUPLICADA");
        assertTrue(first.isPresent());

        Optional<Room> second = roomService.createRoom("Host_B", "duplicada");
        assertTrue(second.isEmpty(), "No debe permitir crear una sala con un código ya existente");

        // La sala original debe seguir intacta, con el host original
        Room stillOriginal = roomService.getRoom("DUPLICADA").orElseThrow();
        assertEquals("Host_A", stillOriginal.getHostName());
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
        assertTrue(room.getActiveRunners().contains("Daniel"));
        assertTrue(room.getActiveRunners().contains("Cristian"));

        roomService.removeRunnerFromRoom(code, "Daniel");
        assertEquals(1, room.getRunnersCount());
        assertFalse(room.getActiveRunners().contains("Daniel"));
    }

    @Test
    @DisplayName("No debe permitir unirse a una sala llena (máximo 6 jugadores)")
    void shouldRejectJoinWhenRoomIsFull() {
        String code = "SALA-LLENA";
        roomService.createRoom("Host", code);

        for (int i = 1; i <= Room.MAX_PLAYERS; i++) {
            RoomJoinResult result = roomService.registerRunnerInRoom(code, "Piloto" + i);
            assertEquals(RoomJoinResult.OK, result);
        }

        RoomJoinResult seventhResult = roomService.registerRunnerInRoom(code, "Piloto7");
        assertEquals(RoomJoinResult.ROOM_FULL, seventhResult);

        Room room = roomService.getRoom(code).orElseThrow();
        assertEquals(Room.MAX_PLAYERS, room.getRunnersCount());
    }

    @Test
    @DisplayName("Solo el host puede iniciar la carrera")
    void onlyHostCanStartRace() {
        String code = "SALA-START";
        roomService.createRoom("HostOficial", code);
        roomService.registerRunnerInRoom(code, "Invitado1");

        var rejected = roomService.startRace(code, "Invitado1");
        assertEquals(edu.eci.arsw.eciracego.model.RoomActionResult.NOT_HOST, rejected);

        var accepted = roomService.startRace(code, "HostOficial");
        assertEquals(edu.eci.arsw.eciracego.model.RoomActionResult.SUCCESS, accepted);

        Room room = roomService.getRoom(code).orElseThrow();
        assertEquals(Room.RoomStatus.IN_PROGRESS, room.getStatus());
    }
}