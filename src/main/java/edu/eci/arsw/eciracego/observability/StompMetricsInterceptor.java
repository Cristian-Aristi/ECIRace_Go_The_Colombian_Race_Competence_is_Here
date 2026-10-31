package edu.eci.arsw.eciracego.observability;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * Interceptor de solo lectura: cuenta cada mensaje que pasa por un canal STOMP y lo deja pasar intacto.
 * Nunca bloquea ni modifica el mensaje; si contar fallara, se ignora para no afectar la carrera.
 */
public class StompMetricsInterceptor implements ChannelInterceptor {

    /** Canal de Spring en el que se instala el interceptor. */
    public enum Channel {
        /** clientInboundChannel: lo que envían los navegadores (CONNECT, SUBSCRIBE, SEND /app/telemetry...). */
        INBOUND,
        /** brokerChannel: lo que el servidor publica (convertAndSend a /topic/...). */
        BROKER,
        /** clientOutboundChannel: cada copia entregada a un cliente suscrito. */
        OUTBOUND
    }

    private final WebSocketMetrics metrics;
    private final Channel channel;

    public StompMetricsInterceptor(WebSocketMetrics metrics, Channel channel) {
        this.metrics = metrics;
        this.channel = channel;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel messageChannel) {
        try {
            SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            switch (channel) {
                case INBOUND -> metrics.inbound(type, destination);
                case BROKER -> {
                    if (type == SimpMessageType.MESSAGE) {
                        metrics.broadcast(destination);
                    }
                }
                case OUTBOUND -> {
                    if (type == SimpMessageType.MESSAGE) {
                        metrics.delivered(destination);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Las métricas nunca deben interrumpir el flujo de mensajes
        }
        return message;
    }
}
