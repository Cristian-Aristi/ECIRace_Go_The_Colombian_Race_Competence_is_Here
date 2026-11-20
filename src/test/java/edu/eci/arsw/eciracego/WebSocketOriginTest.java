package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.config.SameHostOriginInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas con celulares (Sprint 9): detrás de un túnel HTTPS el handshake del WebSocket respondía 403 y
 * el corredor se quedaba en "RECONECTANDO...". El origen se valida por host, no por esquema ni puerto.
 */
class WebSocketOriginTest {

    private final SameHostOriginInterceptor sameHost = new SameHostOriginInterceptor(new String[0]);

    private static boolean handshake(SameHostOriginInterceptor interceptor, String origin, String host, String forwardedHost) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/race-ws-native");
        if (origin != null) req.addHeader("Origin", origin);
        if (host != null) req.addHeader("Host", host);
        if (forwardedHost != null) req.addHeader("X-Forwarded-Host", forwardedHost);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean ok = interceptor.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(res), null, new HashMap<>());
        if (!ok) assertEquals(403, res.getStatus());
        return ok;
    }

    @Test
    void sameMachineAndHttpsTunnelsAreAccepted() {
        assertTrue(handshake(sameHost, "http://localhost:8080", "localhost:8080", null));
        assertTrue(handshake(sameHost, "https://abc123.ngrok-free.app", "abc123.ngrok-free.app", null), "ngrok (esquema distinto)");
        assertTrue(handshake(sameHost, "https://abc-8080.use2.devtunnels.ms", "localhost:8080", "abc-8080.use2.devtunnels.ms"),
                "Túnel que reescribe Host");
        assertTrue(handshake(sameHost, "https://ECIRACE-GO.duckdns.org", "ecirace-go.duckdns.org", null), "Sin importar mayúsculas");
        assertTrue(handshake(sameHost, null, "localhost:8080", null), "Cliente que no es navegador (k6)");
    }

    @Test
    void otherSitesAreRejected() {
        assertFalse(handshake(sameHost, "https://evil.example.com", "abc123.ngrok-free.app", null));
        assertFalse(handshake(sameHost, "https://abc123.ngrok-free.app.evil.com", "abc123.ngrok-free.app", null));
        assertFalse(handshake(sameHost, "null", "localhost:8080", null), "Origin opaco (iframe sandbox, file://)");
        assertFalse(handshake(sameHost, "https://evil.example.com", "localhost:8080", "otro.ngrok-free.app"));
    }

    @Test
    void configuredOriginsAreAccepted() {
        SameHostOriginInterceptor configured = new SameHostOriginInterceptor(new String[]{"https://*.sslip.io"});
        assertTrue(handshake(configured, "https://3-90-1-2.sslip.io", "app:8080", null));
        assertFalse(handshake(configured, "https://evil.example.com", "app:8080", null));
    }
}
