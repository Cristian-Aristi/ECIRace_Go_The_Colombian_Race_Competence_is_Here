package edu.eci.arsw.eciracego.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Instala los interceptores de métricas en los tres canales STOMP.
 *
 * Es un configurador ADICIONAL: Spring combina todos los WebSocketMessageBrokerConfigurer, así que
 * WebSocketConfig (endpoints, broker, latidos) sigue exactamente igual. Este solo agrega interceptores.
 */
@Configuration
public class WebSocketMetricsConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketMetrics metrics;

    public WebSocketMetricsConfig(WebSocketMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.INBOUND));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.OUTBOUND));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Solo agrega un interceptor al canal del broker; el broker lo sigue configurando WebSocketConfig
        registry.configureBrokerChannel()
                .interceptors(new StompMetricsInterceptor(metrics, StompMetricsInterceptor.Channel.BROKER));
    }
}
