package edu.eci.arsw.eciracego.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final StompSecurityInterceptor securityInterceptor;

    public WebSocketEventListener(StompSecurityInterceptor securityInterceptor) {
        this.securityInterceptor = securityInterceptor;
    }

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        Object sessionId = event.getMessage().getHeaders().get("simpSessionId");
        log.info("🔌 Nuevo socket conectado: sessionId={}", sessionId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        log.info("❌ Socket desconectado: sessionId={}", event.getSessionId());
        // Si el navegador se cerró sin enviar DISCONNECT, aquí se borran sus permisos de suscripción
        securityInterceptor.forgetSession(event.getSessionId());
    }
}