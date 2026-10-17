package edu.eci.arsw.eciracego.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
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
