package edu.eci.arsw.eciracego.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Planificador propio (no es un bean) para los latidos STOMP del broker simple.
     * Hilo daemon para no bloquear el apagado de la aplicación.
     */
    private final ThreadPoolTaskScheduler heartbeatScheduler = crearHeartbeatScheduler();

    private static ThreadPoolTaskScheduler crearHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Latidos cada 10 s en ambos sentidos: mantienen vivos los sockets inactivos
        // (sala de espera) a través de ngrok / redes móviles y detectan celulares caídos.
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(heartbeatScheduler);
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 1. Endpoint clásico (con SockJS, útil para localhost)
        registry.addEndpoint("/race-ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // 2. 🚀 NUEVO ENDPOINT NATIVO (Sin SockJS, indispensable para Ngrok)
        registry.addEndpoint("/race-ws-native")
                .setAllowedOriginPatterns("*");
    }
}