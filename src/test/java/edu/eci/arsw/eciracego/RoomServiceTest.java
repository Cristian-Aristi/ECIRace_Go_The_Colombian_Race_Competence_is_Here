package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Room;
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
        Room created = roomService.createRoom("Host_Daniel", null);
        assertNotNull(created);
        assertNotNull(created.getRoomCode());
        assertTrue(created.getRoomCode().startsWith("ECI-"));
        assertEquals("Host_Daniel", created.getHostName());
        assertTrue(roomService.roomExists(created.getRoomCode()));
    }

    @Test
    @DisplayName("Debe crear sala con código preferido normalizado a mayúsculas")
    void shouldCreateRoomWithCustomCode() {
        Room created = roomService.createRoom("Central_Control", "pista-norte");
        assertNotNull(created);
        assertEquals("PISTA-NORTE", created.getRoomCode());
        assertEquals("Central_Control", created.getHostName());
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
}