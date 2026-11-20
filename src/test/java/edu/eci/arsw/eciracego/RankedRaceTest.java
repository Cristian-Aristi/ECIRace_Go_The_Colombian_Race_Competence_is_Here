package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.AntiCheatEngine;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auditoría Sprint 9, tercera ronda: una sola cuenta sumaba 3 victorias en 19 s creando su propia sala.
 * Ahora solo cuentan para la clasificación las carreras con 2 pilotos o más que enviaron posición, de al
 * menos 1 minuto, y en las que el ganador salió a 150 m o más de la meta.
 */
class RankedRaceTest {

    private static final double META_LAT = 4.8050;
    private static final double META_LON = -74.0430;
    /** ~200 m al sur de la meta. */
    private static final double FAR_LAT = META_LAT - 0.0018;
    /** ~100 m al sur de la meta. */
    private static final double NEAR_LAT = META_LAT - 0.0009;

    private RoomServiceImpl rooms;
    private RaceManager manager;
    private UserProfileRepository repo;
    private UserProfile ana;
    private UserProfile beto;
    private Room room;

    @BeforeEach
    void setUp() {
        rooms = new RoomServiceImpl();
        room = rooms.createRoom("Ana", "LIGA").orElseThrow();
        rooms.registerRunnerInRoom("LIGA", "Ana");
        rooms.registerRunnerInRoom("LIGA", "Beto");
        rooms.startRace("LIGA", "Ana", null, META_LAT, META_LON);

        ana = new UserProfile("Ana", null, "Ana", "");
        beto = new UserProfile("Beto", null, "Beto", "");
        repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.findById("Ana")).thenReturn(Optional.of(ana));
        Mockito.when(repo.findById("Beto")).thenReturn(Optional.of(beto));
        manager = new RaceManager(new AntiCheatEngine(), rooms, repo, System::currentTimeMillis);
    }

    private void position(String runner, double lat) {
        Ubication u = new Ubication(runner, lat, META_LON);
        u.setRoomCode("LIGA");
        u.setTimestamp(System.currentTimeMillis());
        assertTrue(manager.updateUbication(u), "Posición de " + runner + " rechazada");
    }

    private void finishWith(String winner, long durationMs) {
        room.setStartedAt(System.currentTimeMillis() - durationMs);
        assertTrue(room.declareWinner(winner));
        manager.saveResults(room);
    }

    @Test
    void realRaceCountsForTheLeaderboard() {
        position("Ana", FAR_LAT);
        position("Beto", FAR_LAT);
        finishWith("Ana", 120_000);

        assertEquals(Boolean.TRUE, room.getRanked());
        assertEquals(1, ana.getWinsCount());
        assertEquals(1, ana.getRacesCount());
        assertEquals(1, beto.getRacesCount());
        assertEquals(0, beto.getWinsCount());
    }

    @Test
    void soloRaceIsPracticeOnly() {
        position("Ana", FAR_LAT); // Beto nunca envió posición
        finishWith("Ana", 120_000);

        assertEquals(Boolean.FALSE, room.getRanked());
        assertEquals(0, ana.getWinsCount());
        assertEquals(0, ana.getRacesCount());
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    @Test
    void tooShortRaceIsPracticeOnly() {
        position("Ana", FAR_LAT);
        position("Beto", FAR_LAT);
        finishWith("Ana", 20_000);

        assertEquals(Boolean.FALSE, room.getRanked());
        assertEquals(0, ana.getWinsCount());
    }

    @Test
    void startingNearTheFinishIsPracticeOnly() {
        position("Ana", NEAR_LAT);
        position("Beto", FAR_LAT);
        finishWith("Ana", 120_000);

        assertEquals(Boolean.FALSE, room.getRanked());
        assertEquals(0, ana.getWinsCount());
    }

    @Test
    void rankedIsUndecidedWhileRacing() {
        assertNull(room.getRanked());
        assertFalse(manager.isRanked(room));
    }
}
