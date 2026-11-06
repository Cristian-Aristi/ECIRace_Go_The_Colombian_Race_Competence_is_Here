package edu.eci.arsw.eciracego.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Métricas del canal en tiempo real (WebSocket + STOMP) de ECIRace.
 *
 * Solo OBSERVA: cuenta sesiones y mensajes, no cambia el flujo de la carrera.
 * Los destinos se agrupan (telemetry, room_telemetry, room_status, gamestate...) para que Prometheus
 * no cree una serie por cada código de sala (alta cardinalidad).
 *
 * En Prometheus:
 *   ecirace_ws_sessions                      sesiones STOMP abiertas
 *   ecirace_ws_connections_total             conexiones establecidas (acumulado)
 *   ecirace_ws_inbound_total{type,destination}   frames que envían los clientes (p. ej. telemetría)
 *   ecirace_ws_broadcast_total{destination}      mensajes que el servidor publica en un topic
 *   ecirace_ws_delivered_total{destination}      mensajes entregados a cada cliente suscrito (fan-out)
 */
@Component
public class WebSocketMetrics {

    private final MeterRegistry registry;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Counter connections;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public WebSocketMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("ecirace.ws.sessions", sessions, Set::size)
                .description("Sesiones STOMP abiertas en este instante")
                .register(registry);
        this.connections = Counter.builder("ecirace.ws.connections")
                .description("Conexiones STOMP establecidas (acumulado)")
                .register(registry);

        // Se registran en 0 desde el arranque para que los paneles no aparezcan vacíos
        inbound(SimpMessageType.MESSAGE, "/app/telemetry", 0);
        broadcast("/topic/rooms/X", 0);
        broadcast("/topic/rooms/X/status", 0);
        broadcast("/topic/gamestate", 0);
    }

    /** Agrupa un destino STOMP en una etiqueta de baja cardinalidad. */
    public static String destinationGroup(String destination) {
        if (destination == null || destination.isBlank()) {
            return "none";
        }
        if (destination.equals("/app/telemetry")) {
            return "telemetry";
        }
        if (destination.equals("/topic/gamestate")) {
            return "gamestate";
        }
        if (destination.startsWith("/topic/rooms/")) {
            return destination.endsWith("/status") ? "room_status" : "room_telemetry";
        }
        if (destination.startsWith("/app/")) {
            return "app_other";
        }
        if (destination.startsWith("/topic/")) {
            return "topic_other";
        }
        return "other";
    }

    public void inbound(SimpMessageType type, String destination) {
        inbound(type, destination, 1);
    }

    public void broadcast(String destination) {
        broadcast(destination, 1);
    }

    public void delivered(String destination) {
        counter("ecirace.ws.delivered", "Mensajes entregados a clientes suscritos, por tipo de destino",
                "destination", destinationGroup(destination), null, null).increment();
    }

    public void connected(String sessionId) {
        if (sessionId != null && sessions.add(sessionId)) {
            connections.increment();
        }
    }

    public void disconnected(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public int openSessions() {
        return sessions.size();
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Object id = event.getMessage().getHeaders().get("simpSessionId");
        connected(id != null ? id.toString() : null);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    private void inbound(SimpMessageType type, String destination, double amount) {
        String typeTag = (type != null) ? type.name().toLowerCase(Locale.ROOT) : "unknown";
        counter("ecirace.ws.inbound", "Frames STOMP recibidos de los clientes, por tipo y destino",
                "type", typeTag, "destination", destinationGroup(destination)).increment(amount);
    }

    private void broadcast(String destination, double amount) {
        counter("ecirace.ws.broadcast", "Mensajes publicados por el servidor en un topic, por tipo de destino",
                "destination", destinationGroup(destination), null, null).increment(amount);
    }

    private Counter counter(String name, String description, String k1, String v1, String k2, String v2) {
        String key = name + '|' + v1 + '|' + v2;
        return counters.computeIfAbsent(key, k -> {
            Counter.Builder builder = Counter.builder(name).description(description).tag(k1, v1);
            if (k2 != null) {
                builder.tag(k2, v2);
            }
            return builder.register(registry);
        });
    }
}
