package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.model.Ubication;
import edu.eci.arsw.eciracego.observability.ProjectGauges;
import edu.eci.arsw.eciracego.observability.StompMetricsInterceptor;
import edu.eci.arsw.eciracego.observability.WebSocketMetrics;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.RoomService;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de las métricas del proyecto (WebSocket, salas, corredores, usuarios y pool RaceWorker).
 */
class ProjectObservabilityTest {

    private SimpleMeterRegistry registry;
    private WebSocketMetrics metrics;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WebSocketMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private static Message<byte[]> stomp(SimpMessageType type, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    void destinationsAreGroupedWithLowCardinality() {
        assertEquals("telemetry", WebSocketMetrics.destinationGroup("/app/telemetry"));
        assertEquals("room_telemetry", WebSocketMetrics.destinationGroup("/topic/rooms/ECI-1234"));
        assertEquals("room_status", WebSocketMetrics.destinationGroup("/topic/rooms/ECI-1234/status"));
        assertEquals("gamestate", WebSocketMetrics.destinationGroup("/topic/gamestate"));
        assertEquals("topic_other", WebSocketMetrics.destinationGroup("/topic/broadcast"));
        assertEquals("none", WebSocketMetrics.destinationGroup(null));
    }

    @Test
    void interceptorsCountWithoutModifyingTheMessage() {
        StompMetricsInterceptor inbound = new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.INBOUND);
        StompMetricsInterceptor broker = new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.BROKER);
        StompMetricsInterceptor outbound = new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.OUTBOUND);

        Message<byte[]> telemetry = stomp(SimpMessageType.MESSAGE, "/app/telemetry");
        assertSame(telemetry, inbound.preSend(telemetry, null), "El mensaje debe pasar intacto");
        inbound.preSend(stomp(SimpMessageType.MESSAGE, "/app/telemetry"), null);
        inbound.preSend(stomp(SimpMessageType.SUBSCRIBE, "/topic/rooms/ABC"), null);

        broker.preSend(stomp(SimpMessageType.MESSAGE, "/topic/rooms/ABC"), null);
        broker.preSend(stomp(SimpMessageType.MESSAGE, "/topic/rooms/ABC/status"), null);
        outbound.preSend(stomp(SimpMessageType.MESSAGE, "/topic/gamestate"), null);
        outbound.preSend(stomp(SimpMessageType.HEARTBEAT, null), null); // no es MESSAGE: no se cuenta

        assertEquals(2.0, count("ecirace.ws.inbound", "type", "message", "destination", "telemetry"));
        assertEquals(1.0, count("ecirace.ws.inbound", "type", "subscribe", "destination", "room_telemetry"));
        assertEquals(1.0, count("ecirace.ws.broadcast", "destination", "room_telemetry"));
        assertEquals(1.0, count("ecirace.ws.broadcast", "destination", "room_status"));
        assertEquals(1.0, count("ecirace.ws.delivered", "destination", "gamestate"));
    }

    @Test
    void sessionsAreCountedOnceAndNeverGoNegative() {
        metrics.connected("s1");
        metrics.connected("s1");
        metrics.connected("s2");
        assertEquals(2.0, registry.get("ecirace.ws.sessions").gauge().value());
        assertEquals(2.0, registry.get("ecirace.ws.connections").counter().count());

        metrics.disconnected("s1");
        metrics.disconnected("s1");
        metrics.disconnected("desconocida");
        assertEquals(1.0, registry.get("ecirace.ws.sessions").gauge().value());
    }

    @Test
    void projectGaugesReadTheCurrentGameState() {
        RoomService rooms = new RoomServiceImpl(); // trae CAMPUS-ECI en WAITING
        rooms.createRoom("Host", "SALA-1");
        rooms.startRace("SALA-1", "Host", 4.7828, -74.0430);

        RaceManager raceManager = Mockito.mock(RaceManager.class);
        Mockito.when(raceManager.getGlobalState())
                .thenReturn(List.of(new Ubication("a", 4.7, -74.0), new Ubication("b", 4.7, -74.0)));

        UserProfileRepository users = Mockito.mock(UserProfileRepository.class);
        Mockito.when(users.count()).thenReturn(3L);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();

        new ProjectGauges(registry, rooms, raceManager, users, executor);

        assertEquals(1.0, registry.get("ecirace.rooms").tag("status", "waiting").gauge().value());
        assertEquals(1.0, registry.get("ecirace.rooms").tag("status", "in_progress").gauge().value());
        assertEquals(0.0, registry.get("ecirace.rooms").tag("status", "finished").gauge().value());
        assertEquals(2.0, registry.get("ecirace.runners.tracked").gauge().value());
        assertEquals(3.0, registry.get("ecirace.users.registered").gauge().value());
        assertEquals(4.0, registry.get("ecirace.executor.pool.max").gauge().value());
        assertEquals(0.0, registry.get("ecirace.executor.queue.size").gauge().value());
    }
}
