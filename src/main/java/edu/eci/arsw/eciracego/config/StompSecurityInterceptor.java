package edu.eci.arsw.eciracego.config;

import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.security.RateLimiter;
import edu.eci.arsw.eciracego.security.TokenService;
import edu.eci.arsw.eciracego.service.RoomService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seguridad del canal STOMP (lo que envían los navegadores):
 * <ul>
 *   <li>CONNECT: si trae "Authorization: Bearer &lt;token&gt;", el token se valida y el piloto queda
 *       como Principal de la sesión. Un token inválido cierra la conexión.</li>
 *   <li>SEND: solo a /app/** (antes se podía publicar directo en /topic/** y falsificar el estado de una
 *       sala). /app/telemetry exige sesión y tiene límite de 5 paquetes/s por conexión.</li>
 *   <li>SUBSCRIBE: solo a /topic/rooms/{code} y /topic/rooms/{code}/status, y únicamente corredores
 *       de esa sala o su host (piloto host o cabecera "hostKey").</li>
 *   <li>Salida ({@link #outboundGuard()}): el permiso se vuelve a revisar ANTES de entregar cada mensaje
 *       de una sala. Antes solo se revisaba al suscribirse: quien salía de la sala (o el host lo retiraba)
 *       seguía recibiendo el GPS en vivo de los corredores (auditoría Sprint 9, tercera ronda).</li>
 * </ul>
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/([A-Z0-9_\\-]{3,20})(/status)?$");
    private static final String SUBSCRIPTIONS_ATTR = "ecirace.subscriptions";
    private static final int MAX_SUBSCRIPTIONS = 10;

    /** Con qué identidad se aceptó una suscripción: el piloto de la sesión y/o la clave del host. */
    private record RoomGrant(String roomCode, String user, String hostKey) {
    }

    private final TokenService tokenService;
    private final RoomService roomService;
    private final RateLimiter rateLimiter;
    /** sesión STOMP -> (id de suscripción -> permiso). Se borra al desuscribirse o desconectarse. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, RoomGrant>> grants = new ConcurrentHashMap<>();
    private final ChannelInterceptor outboundGuard = new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            return filterOutbound(message);
        }
    };

    public StompSecurityInterceptor(TokenService tokenService, RoomService roomService, RateLimiter rateLimiter) {
        this.tokenService = tokenService;
        this.roomService = roomService;
        this.rateLimiter = rateLimiter;
    }

    /** Interceptor del canal de SALIDA (servidor -> navegador). Lo registra WebSocketConfig. */
    public ChannelInterceptor outboundGuard() {
        return outboundGuard;
    }

    /** Olvida los permisos de una sesión (también se llama al cerrarse el socket sin DISCONNECT). */
    public void forgetSession(String sessionId) {
        if (sessionId != null) {
            grants.remove(sessionId);
            rateLimiter.forget("ws:" + sessionId);
        }
    }

    /**
     * Entrega un mensaje de sala solo si la suscripción SIGUE teniendo permiso. Lo que no es un mensaje de
     * sala (CONNECTED, RECEIPT, ERROR...) pasa sin cambios. Sin permiso registrado, se descarta.
     */
    Message<?> filterOutbound(Message<?> message) {
        MessageHeaders headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
            return message;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        if (destination == null || !ROOM_TOPIC.matcher(destination).matches()) {
            return message;
        }
        Map<String, RoomGrant> bySubscription = grants.get(String.valueOf(SimpMessageHeaderAccessor.getSessionId(headers)));
        RoomGrant grant = bySubscription == null ? null
                : bySubscription.get(String.valueOf(SimpMessageHeaderAccessor.getSubscriptionId(headers)));
        if (grant == null || !roomService.canView(grant.roomCode(), grant.user(), grant.hostKey())) {
            return null; // ya no es corredor ni host de la sala: no recibe nada más
        }
        return message;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // latidos y mensajes internos
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> {
                if (!authorizeSend(accessor)) {
                    return null; // se descarta en silencio (exceso de telemetría)
                }
            }
            case DISCONNECT -> forgetSession(accessor.getSessionId());
            case UNSUBSCRIBE -> {
                Map<String, RoomGrant> bySubscription = grants.get(String.valueOf(accessor.getSessionId()));
                if (bySubscription != null) {
                    bySubscription.remove(String.valueOf(accessor.getSubscriptionId()));
                }
            }
            default -> {
                // ACK, NACK...: sin restricciones
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) {
            return; // conexión anónima: solo podrá suscribirse con la clave del host
        }
        String token = TokenService.bearer(header);
        String username = tokenService.authenticate(token)
                .orElseThrow(() -> new MessageDeliveryException("Sesión inválida o vencida"))
                .getUsername();
        accessor.setUser(new AuthenticatedUser(username));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : ROOM_TOPIC.matcher(destination);
        if (m == null || !m.matches()) {
            throw new MessageDeliveryException("Destino no permitido");
        }
        String user = principalName(accessor.getUser());
        String hostKey = accessor.getFirstNativeHeader("hostKey");
        if (!roomService.canView(m.group(1), user, hostKey)) {
            throw new MessageDeliveryException("No tienes acceso a esta sala");
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            AtomicInteger count = (AtomicInteger) attrs.computeIfAbsent(SUBSCRIPTIONS_ATTR, k -> new AtomicInteger());
            if (count.incrementAndGet() > MAX_SUBSCRIPTIONS) {
                throw new MessageDeliveryException("Demasiadas suscripciones");
            }
        }
        // Se recuerda con qué identidad se aceptó: filterOutbound la vuelve a revisar en cada mensaje
        grants.computeIfAbsent(String.valueOf(accessor.getSessionId()), k -> new ConcurrentHashMap<>())
                .put(String.valueOf(accessor.getSubscriptionId()), new RoomGrant(m.group(1), user, hostKey));
    }

    private boolean authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            throw new MessageDeliveryException("Solo se puede enviar a /app/**");
        }
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException("Debes iniciar sesión para enviar telemetría");
        }
        // Un GPS real entrega ~1 fix por segundo: 5/s con ráfaga de 10 deja margen de sobra
        return rateLimiter.tryAcquire("ws:" + accessor.getSessionId(), 10, 5.0);
    }

    private static String principalName(Principal principal) {
        return principal != null ? principal.getName() : null;
    }
}
