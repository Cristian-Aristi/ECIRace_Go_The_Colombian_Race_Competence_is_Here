package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.lab.LabController;
import edu.eci.arsw.eciracego.lab.LabFilter;
import edu.eci.arsw.eciracego.lab.LabState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** POST /admin/config del modo laboratorio: token, validación del cuerpo y efecto en el filtro. */
class LabConfigTest {

    private static final String TOKEN = "token-de-laboratorio-largo";

    private LabState state;
    private LabController controller;

    @BeforeEach
    void setUp() {
        state = new LabState(e -> { }, new SimpleMeterRegistry(), 0, 0);
        controller = new LabController(state, TOKEN);
    }

    private static Map<String, Object> cuerpo(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private int config(Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> r = controller.config(TOKEN, body);
        return r.getStatusCode().value();
    }

    @Test
    void sinTokenValidoNadieCambiaLaConfiguracion() {
        assertEquals(403, controller.config(null, cuerpo("ready", false)).getStatusCode().value());
        assertEquals(403, controller.config("otro-token-cualquiera", cuerpo("ready", false)).getStatusCode().value());
        LabController corto = new LabController(state, "corto");
        assertEquals(403, corto.config("corto", cuerpo("ready", false)).getStatusCode().value());
        LabController vacio = new LabController(state, null);
        assertEquals(403, vacio.config("", cuerpo()).getStatusCode().value());
        assertTrue(state.isReady());
    }

    @Test
    void rechazaCuerposInvalidos() {
        assertEquals(400, config(null));
        assertEquals(400, config(cuerpo("otra", 1)));
        assertEquals(400, config(cuerpo("ready", "si")));
        assertEquals(400, config(cuerpo("delay_ms", "100")));
        assertEquals(400, config(cuerpo("delay_ms", -1)));
        assertEquals(400, config(cuerpo("delay_ms", 5000)));
        assertEquals(400, config(cuerpo("delay_ms", Double.NaN)));
        assertEquals(400, config(cuerpo("capacity", 2.5)));
        assertEquals(400, config(cuerpo("capacity", -1)));
        assertEquals(400, config(cuerpo("capacity", 5000)));
        assertEquals(400, config(cuerpo("capacity", "3")));
    }

    @Test
    void aplicaLaFallaYLaEsperaSimulada() throws Exception {
        ResponseEntity<Map<String, Object>> r = controller.config(TOKEN,
                cuerpo("ready", false, "delay_ms", 1.4, "capacity", 3));
        assertEquals(200, r.getStatusCode().value());
        assertEquals(Boolean.FALSE, r.getBody().get("ready"));
        assertEquals(1, r.getBody().get("delay_ms"));
        assertEquals(3, r.getBody().get("capacity"));
        assertEquals(200, config(cuerpo()));

        LabFilter filtro = new LabFilter(state);
        MockHttpServletResponse caida = new MockHttpServletResponse();
        filtro.doFilter(new MockHttpServletRequest("GET", "/api/rooms"), caida, new MockFilterChain());
        assertEquals(503, caida.getStatus());

        assertEquals(200, config(cuerpo("ready", true)));
        MockHttpServletResponse lenta = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filtro.doFilter(new MockHttpServletRequest("GET", "/api/rooms"), lenta, chain);
        assertEquals(200, lenta.getStatus());
        assertNotNull(chain.getRequest());
        assertEquals(0, state.getActive());
        assertEquals(3, state.getCapacity());

        // Si interrumpen el hilo durante la espera, se responde 503 y se libera el cupo
        Thread.currentThread().interrupt();
        MockHttpServletResponse interrumpida = new MockHttpServletResponse();
        filtro.doFilter(new MockHttpServletRequest("GET", "/api/rooms"), interrumpida, new MockFilterChain());
        assertTrue(Thread.interrupted());
        assertEquals(503, interrumpida.getStatus());
        assertEquals(0, state.getActive());

        // Los archivos estáticos no pasan por el modo laboratorio
        assertEquals(200, config(cuerpo("ready", false)));
        MockHttpServletResponse estatico = new MockHttpServletResponse();
        filtro.doFilter(new MockHttpServletRequest("GET", "/index.html"), estatico, new MockFilterChain());
        assertEquals(200, estatico.getStatus());
    }

    @Test
    void valoresFueraDeRangoAlCrearElEstado() {
        assertThrows(IllegalArgumentException.class, () -> new LabState(e -> { }, new SimpleMeterRegistry(), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LabState(e -> { }, new SimpleMeterRegistry(), 0, 2000));
    }
}
