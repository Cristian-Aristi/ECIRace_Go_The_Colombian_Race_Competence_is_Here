package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.config.StompSecurityInterceptor;
import edu.eci.arsw.eciracego.model.Room;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.security.RateLimiter;
import edu.eci.arsw.eciracego.security.TokenService;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StompSecurityInterceptorTest {

    private RoomServiceImpl rooms;
    private TokenService tokens;
    private StompSecurityInterceptor interceptor;
    private UserProfile corredor;
    private Room room;

    @BeforeEach
    void setUp() {
        rooms = new RoomServiceImpl();
        room = rooms.createRoom(null, "SALA-WS").orElseThrow();
        rooms.registerRunnerInRoom("SALA-WS", "Corredor");

        corredor = new UserProfile("Corredor", null, "Corredor", "");
        corredor.rotateSessionKey();
        UserProfileRepository repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.findById("Corredor")).thenReturn(Optional.of(corredor));
        tokens = new TokenService("un-secreto-de-pruebas-de-al-menos-32-bytes!!", repo);
        interceptor = new StompSecurityInterceptor(tokens, rooms, new RateLimiter());
    }

    private static Message<byte[]> frame(StompCommand command, String destination, String user, String... headers) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(command);
        acc.setSessionId("s1");
        acc.setSessionAttributes(new HashMap<>());
        if (destination != null) acc.setDestination(destination);
        if (user != null) acc.setUser(new AuthenticatedUser(user));
        for (int i = 0; i + 1 < headers.length; i += 2) acc.addNativeHeader(headers[i], headers[i + 1]);
        acc.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }

    @Test
    void connectWithValidTokenSetsThePrincipal() {
        Message<byte[]> msg = frame(StompCommand.CONNECT, null, null, "Authorization", "Bearer " + tokens.issue(corredor));
        interceptor.preSend(msg, null);
        assertEquals("Corredor", StompHeaderAccessor.wrap(msg).getUser().getName());
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.CONNECT, null, null, "Authorization", "Bearer basura"), null));
    }

    @Test
    void clientsCannotPublishDirectlyToTopics() {
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.SEND, "/topic/rooms/SALA-WS/status", "Corredor"), null));
    }

    @Test
    void telemetryRequiresSessionAndIsRateLimited() {
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.SEND, "/app/telemetry", null), null));
        int accepted = 0;
        for (int i = 0; i < 50; i++) {
            if (interceptor.preSend(frame(StompCommand.SEND, "/app/telemetry", "Corredor"), null) != null) accepted++;
        }
        assertTrue(accepted >= 10 && accepted < 20, "Ráfaga limitada, aceptados: " + accepted);
    }

    @Test
    void subscriptionsAreLimitedToRoomMembersAndHost() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS", "Corredor"), null));
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS", "Extrano"), null));
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS/status", null), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS/status", null,
                "hostKey", room.getHostKey()), null));
        assertThrows(MessageDeliveryException.class, () ->
                interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/gamestate", "Corredor"), null));
    }

    // ---- Auditoría, 3.ª ronda: el permiso se revisa también al ENTREGAR cada mensaje ----

    private void subscribe(String session, String subId, String destination, String user, String hostKey) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setSessionId(session);
        acc.setSubscriptionId(subId);
        acc.setSessionAttributes(new HashMap<>());
        acc.setDestination(destination);
        if (user != null) acc.setUser(new AuthenticatedUser(user));
        if (hostKey != null) acc.addNativeHeader("hostKey", hostKey);
        acc.setLeaveMutable(true);
        assertNotNull(interceptor.preSend(MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders()), null));
    }

    private Message<?> deliver(String session, String subId, String destination) {
        SimpMessageHeaderAccessor acc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        acc.setSessionId(session);
        acc.setSubscriptionId(subId);
        acc.setDestination(destination);
        return interceptor.outboundGuard().preSend(MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders()), null);
    }

    @Test
    void runnerWhoLeftStopsReceivingTheRoomTelemetry() {
        subscribe("espia", "sub-1", "/topic/rooms/SALA-WS", "Corredor", null);
        assertNotNull(deliver("espia", "sub-1", "/topic/rooms/SALA-WS"), "Mientras es corredor recibe");

        rooms.removeRunnerFromRoom("SALA-WS", "Corredor");
        assertNull(deliver("espia", "sub-1", "/topic/rooms/SALA-WS"), "Salió de la sala y seguía recibiendo el GPS");
    }

    @Test
    void kickedRunnerStopsReceivingAndHostKeepsReceiving() {
        subscribe("bot", "s", "/topic/rooms/SALA-WS/status", "Corredor", null);
        subscribe("tablero", "s", "/topic/rooms/SALA-WS", null, room.getHostKey());

        rooms.kickRunner("SALA-WS", null, room.getHostKey(), "Corredor");
        assertNull(deliver("bot", "s", "/topic/rooms/SALA-WS/status"));
        assertNotNull(deliver("tablero", "s", "/topic/rooms/SALA-WS"), "El host con su clave sigue recibiendo");
    }

    @Test
    void unknownSubscriptionsAndClosedSessionsReceiveNothing() {
        assertNull(deliver("nadie", "x", "/topic/rooms/SALA-WS"), "Sin permiso registrado no se entrega");

        subscribe("s2", "a", "/topic/rooms/SALA-WS", "Corredor", null);
        interceptor.forgetSession("s2");
        assertNull(deliver("s2", "a", "/topic/rooms/SALA-WS"));

        // Lo que no es un mensaje de sala pasa sin cambios
        assertNotNull(deliver("nadie", "x", "/topic/otra-cosa"));
    }
}
