package edu.eci.arsw.eciracego.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompSecurityInterceptor securityInterceptor;
    private final String[] allowedOrigins;

    /**
     * Planificador propio (no es un bean) para los latidos STOMP del broker simple.
     * Hilo daemon para no bloquear el apagado de la aplicación.
     */
    private final ThreadPoolTaskScheduler heartbeatScheduler = crearHeartbeatScheduler();

    public WebSocketConfig(StompSecurityInterceptor securityInterceptor,
                           @Value("${ecirace.allowed-origins:}") String allowedOrigins) {
        this.securityInterceptor = securityInterceptor;
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    static String[] parseOrigins(String raw) {
        return raw == null ? new String[0] : Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

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
        // (sala de espera) a través de proxies / redes móviles y detectan celulares caídos.
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(heartbeatScheduler);
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Solo el endpoint nativo (el de SockJS ya no lo usa ningún cliente).
        // Sin orígenes configurados, Spring acepta únicamente el MISMO origen que sirve la página.
        registry.addEndpoint("/race-ws-native").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(securityInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Un paquete de telemetría pesa ~200 bytes: 16 KB sobra y evita mensajes gigantes
        registration.setMessageSizeLimit(16 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(15_000);
    }
}
