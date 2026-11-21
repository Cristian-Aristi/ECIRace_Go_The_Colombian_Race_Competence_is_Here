package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.config.LegacyDataCleanup;
import edu.eci.arsw.eciracego.config.SameHostOriginInterceptor;
import edu.eci.arsw.eciracego.config.StompSecurityInterceptor;
import edu.eci.arsw.eciracego.config.WebCorsConfig;
import edu.eci.arsw.eciracego.config.WebSocketEventListener;
import edu.eci.arsw.eciracego.controller.ApiExceptionHandler;
import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.dto.CreateRoomRequest;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.observability.StompMetricsInterceptor;
import edu.eci.arsw.eciracego.observability.WebSocketMetrics;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.RateLimitFilter;
import edu.eci.arsw.eciracego.security.RateLimiter;
import edu.eci.arsw.eciracego.security.RequestPaths;
import edu.eci.arsw.eciracego.security.SecurityHeadersFilter;
import edu.eci.arsw.eciracego.service.RaceManager;
import edu.eci.arsw.eciracego.service.RoomJanitor;
import edu.eci.arsw.eciracego.service.RoomService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/** Piezas de infraestructura: métricas de WebSocket, filtros HTTP, origen del handshake, CORS y tareas programadas. */
class InfraestructuraTest {

    private static Message<byte[]> mensaje(SimpMessageType tipo, String destino, String sesion) {
        SimpMessageHeaderAccessor acc = SimpMessageHeaderAccessor.create(tipo);
        if (destino != null) acc.setDestination(destino);
        if (sesion != null) acc.setSessionId(sesion);
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }

    @Test
    void metricasDeWebSocketPorTipoDeDestino() {
        WebSocketMetrics m = new WebSocketMetrics(new SimpleMeterRegistry());
        assertEquals("none", WebSocketMetrics.destinationGroup(null));
        assertEquals("none", WebSocketMetrics.destinationGroup(" "));
        assertEquals("telemetry", WebSocketMetrics.destinationGroup("/app/telemetry"));
        assertEquals("gamestate", WebSocketMetrics.destinationGroup("/topic/gamestate"));
        assertEquals("room_status", WebSocketMetrics.destinationGroup("/topic/rooms/A/status"));
        assertEquals("room_telemetry", WebSocketMetrics.destinationGroup("/topic/rooms/A"));
        assertEquals("app_other", WebSocketMetrics.destinationGroup("/app/otra"));
        assertEquals("topic_other", WebSocketMetrics.destinationGroup("/topic/otra"));
        assertEquals("other", WebSocketMetrics.destinationGroup("/queue/x"));

        m.inbound(null, "/app/telemetry");
        m.inbound(SimpMessageType.MESSAGE, "/app/telemetry");
        m.broadcast("/topic/rooms/A");
        m.delivered("/topic/rooms/A");

        m.connected(null);
        m.connected("s1");
        m.connected("s1");
        m.onConnected(new SessionConnectedEvent(this, mensaje(SimpMessageType.CONNECT_ACK, null, "s2")));
        m.onConnected(new SessionConnectedEvent(this, mensaje(SimpMessageType.CONNECT_ACK, null, null)));
        assertEquals(2, m.openSessions());
        m.disconnected(null);
        m.onDisconnected(new SessionDisconnectEvent(this, mensaje(SimpMessageType.DISCONNECT, null, "s1"), "s1", CloseStatus.NORMAL));
        assertEquals(1, m.openSessions());
    }

    @Test
    void interceptorDeMetricasNuncaRompeElFlujo() {
        WebSocketMetrics m = new WebSocketMetrics(new SimpleMeterRegistry());
        Message<byte[]> msg = mensaje(SimpMessageType.MESSAGE, "/topic/rooms/A", "s1");
        Message<byte[]> ack = mensaje(SimpMessageType.CONNECT_ACK, null, "s1");
        for (StompMetricsInterceptor.Channel canal : StompMetricsInterceptor.Channel.values()) {
            StompMetricsInterceptor i = new StompMetricsInterceptor(m, canal);
            assertSame(msg, i.preSend(msg, null));
            assertSame(ack, i.preSend(ack, null));
        }
        WebSocketMetrics roto = Mockito.mock(WebSocketMetrics.class);
        Mockito.doThrow(new IllegalStateException("registro caído")).when(roto).inbound(any(), any());
        assertSame(msg, new StompMetricsInterceptor(roto, StompMetricsInterceptor.Channel.INBOUND).preSend(msg, null));
    }

    @Test
    void eventosDeConexionYLimpiezaProgramada() {
        StompSecurityInterceptor seguridad = Mockito.mock(StompSecurityInterceptor.class);
        WebSocketEventListener listener = new WebSocketEventListener(seguridad);
        listener.handleConnect(new SessionConnectedEvent(this, mensaje(SimpMessageType.CONNECT_ACK, null, "s9")));
        listener.handleDisconnect(new SessionDisconnectEvent(this, mensaje(SimpMessageType.DISCONNECT, null, "s9"), "s9", CloseStatus.NORMAL));
        Mockito.verify(seguridad).forgetSession("s9");

        RoomService salas = Mockito.mock(RoomService.class);
        RaceManager carreras = Mockito.mock(RaceManager.class);
        Mockito.when(salas.cleanup(anyLong())).thenReturn(List.of("VIEJA"));
        new RoomJanitor(salas, carreras).cleanup();
        Mockito.verify(carreras).cleanup(Mockito.eq(List.of("VIEJA")), anyLong());
    }

    @Test
    void limpiezaDePerfilesSemillaSoloSiSiguenIntactos() {
        UserProfileRepository repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.findById(anyString())).thenReturn(Optional.empty());
        UserProfile semilla = new UserProfile("Daniel", "daniel@escuelaing.edu.co", "Daniel", "", 125, 48, 1.9);
        UserProfile modificado = new UserProfile("Cristian", "cristian@escuelaing.edu.co", "Cristian", "", 126, 48, 1.9);
        UserProfile conGoogle = new UserProfile("Santiago", "santiago@escuelaing.edu.co", "Santiago", "", 125, 48, 1.9);
        conGoogle.setGoogleSubject("g-123");
        Mockito.when(repo.findById("Daniel")).thenReturn(Optional.of(semilla));
        Mockito.when(repo.findById("Cristian")).thenReturn(Optional.of(modificado));
        Mockito.when(repo.findById("Santiago")).thenReturn(Optional.of(conGoogle));

        new LegacyDataCleanup(repo).removeSeedProfiles();

        Mockito.verify(repo).delete(semilla);
        Mockito.verify(repo, Mockito.never()).delete(modificado);
        Mockito.verify(repo, Mockito.never()).delete(conGoogle);

        UserProfile otroCorreo = new UserProfile("Daniel", "otro@mail.com", "Daniel", "", 125, 48, 1.9);
        UserProfile otrasVictorias = new UserProfile("Cristian", "cristian@escuelaing.edu.co", "C", "", 125, 47, 1.9);
        Mockito.when(repo.findById("Daniel")).thenReturn(Optional.of(otroCorreo));
        Mockito.when(repo.findById("Cristian")).thenReturn(Optional.of(otrasVictorias));
        new LegacyDataCleanup(repo).removeSeedProfiles();
        Mockito.verify(repo, Mockito.never()).delete(otroCorreo);
    }

    private static boolean handshake(SameHostOriginInterceptor i, String origin, String host, String forwarded) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/race-ws-native");
        if (origin != null) req.addHeader("Origin", origin);
        if (host != null) req.addHeader("Host", host);
        if (forwarded != null) req.addHeader("X-Forwarded-Host", forwarded);
        MockHttpServletResponse res = new MockHttpServletResponse();
        ServletServerHttpRequest r = new ServletServerHttpRequest(req);
        ServletServerHttpResponse s = new ServletServerHttpResponse(res);
        boolean ok = i.beforeHandshake(r, s, null, new HashMap<>());
        i.afterHandshake(r, s, null, null);
        return ok;
    }

    @Test
    void origenDelHandshakePorHostIpv6YListas() {
        SameHostOriginInterceptor i = new SameHostOriginInterceptor(new String[]{"https://*.permitido.com"});
        assertTrue(handshake(i, "https://app.permitido.com", "otro.com", null));
        assertTrue(handshake(i, "http://[::1]:8080", "[::1]:8080", null));
        assertFalse(handshake(i, "http://[::1]:8080", "[::1", null));
        assertTrue(handshake(i, "https://publico.com", "interno:8080", "publico.com, proxy.local"));
        assertTrue(handshake(i, "https://publico.com", "interno", "publico.com:443"));
        assertFalse(handshake(i, "https://malo.com", null, null));
        assertFalse(handshake(i, "https://malo.com", "  ", null));
        assertFalse(handshake(i, "file:///etc/passwd", "file", null));
        assertFalse(handshake(i, "http://espacio malo.com", "espacio", null));
    }

    @Test
    void corsSoloSiHayOrigenesConfigurados() {
        new WebCorsConfig("").addCorsMappings(new CorsRegistry());
        new WebCorsConfig(null).addCorsMappings(new CorsRegistry());
        CorsRegistry registry = new CorsRegistry();
        new WebCorsConfig("https://a.com, ,https://b.com").addCorsMappings(registry);
        assertNotNull(registry);
    }

    private static MockHttpServletResponse filtrar(jakarta.servlet.Filter f, String method, String path, String host, String forwarded) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr("10.1.1.1");
        if (host != null) req.addHeader("Host", host);
        if (forwarded != null) req.addHeader("X-Forwarded-Host", forwarded);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void cabecerasDeSeguridadYCachePorTipoDeRuta() throws Exception {
        SecurityHeadersFilter f = new SecurityHeadersFilter();
        assertEquals("no-store", filtrar(f, "GET", "/api/rooms", "ecirace.test:8080", null).getHeader("Cache-Control"));
        assertEquals("no-cache", filtrar(f, "GET", "/", null, null).getHeader("Cache-Control"));
        assertEquals("no-cache", filtrar(f, "GET", "/lobby.html", null, null).getHeader("Cache-Control"));
        MockHttpServletResponse js = filtrar(f, "GET", "/js/lobby.js", "evil.com/<script>", "publico.com, interno");
        assertNull(js.getHeader("Cache-Control"));
        String csp = js.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("wss://publico.com"));
        assertFalse(csp.contains("evil.com"));
    }

    @Test
    void limiteDeSolicitudesPorIpYRutasSensibles() throws Exception {
        RateLimiter limiter = new RateLimiter();
        RateLimitFilter apagado = new RateLimitFilter(limiter, false);
        assertEquals(200, filtrar(apagado, "POST", "/api/auth/guest", null, null).getStatus());

        RateLimitFilter f = new RateLimitFilter(limiter, true);
        assertEquals(200, filtrar(f, "GET", "/index.html", null, null).getStatus());
        int bloqueadas = 0;
        for (int i = 0; i < 12; i++) {
            MockHttpServletResponse r = filtrar(f, "POST", "/api/auth/guest", null, null);
            if (r.getStatus() == 429) {
                bloqueadas++;
                assertEquals("6", r.getHeader("Retry-After"));
            }
        }
        assertTrue(bloqueadas >= 1);
        int lentas = 0;
        for (int i = 0; i < 250; i++) {
            if (filtrar(f, "GET", "/api/rooms", null, null).getStatus() == 429) lentas++;
        }
        assertTrue(lentas >= 1);
        limiter.forget("api:10.1.1.1");
        limiter.evictIdle();
        assertEquals(200, filtrar(f, "GET", "/api/rooms", null, null).getStatus());
    }

    @Test
    void rutasConCodificacionInvalida() {
        MockHttpServletRequest malo = new MockHttpServletRequest("GET", "/api/%zz");
        assertNotNull(RequestPaths.of(malo));
    }

    @Test
    void erroresDeLaApiYObjetosDeTransferencia() {
        ApiExceptionHandler h = new ApiExceptionHandler();
        assertEquals(400, h.badRequest(new IllegalArgumentException("x")).getStatusCode().value());
        assertFalse(h.api(new ApiException(HttpStatus.GONE, "ya no")).getBody().containsKey("code"));
        assertEquals("X", h.api(new ApiException(HttpStatus.GONE, "X", "ya no")).getBody().get("code"));

        AuthRequest a = new AuthRequest();
        a.setIdToken("t");
        a.setUsername("u");
        assertEquals("t", a.getIdToken());
        assertEquals("u", a.getUsername());
        CreateRoomRequest c = new CreateRoomRequest();
        c.setPreferredCode("SALA");
        assertEquals("SALA", c.getPreferredCode());

        UserProfile p = new UserProfile();
        p.setUsername("Ana");
        p.setRacesCount(4);
        p.setWinsCount(2);
        p.setRating(2.5);
        p.registerRace(false);
        assertEquals("Ana", p.getUsername());
        assertEquals(5, p.getRacesCount());
        assertEquals(2.0, p.getRating());
    }
}
