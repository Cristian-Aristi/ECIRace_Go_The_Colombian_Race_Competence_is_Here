package edu.eci.arsw.eciracego.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS de la API. El juego se sirve desde el mismo origen, así que por defecto NO se permite ningún
 * otro (antes era "*"). Para un front en otro dominio o un túnel (ngrok), definir
 * ECIRACE_ALLOWED_ORIGINS=https://mi-dominio,https://otro (también aplica al WebSocket).
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public WebCorsConfig(@Value("${ecirace.allowed-origins:}") String origins) {
        this.origins = WebSocketConfig.parseOrigins(origins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("Authorization", "Content-Type", "X-Host-Key", "ngrok-skip-browser-warning")
                .allowCredentials(false)
                .maxAge(600);
    }
}
