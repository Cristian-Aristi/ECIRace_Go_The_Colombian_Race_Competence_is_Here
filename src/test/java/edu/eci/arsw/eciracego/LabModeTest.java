package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.lab.LabFilter;
import edu.eci.arsw.eciracego.lab.LabReadyController;
import edu.eci.arsw.eciracego.lab.LabState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas del modo laboratorio (equivalente a /admin/config, cupos y espera de app/server.py).
 */
class LabModeTest {

    private final List<Object> events = new ArrayList<>();
    private SimpleMeterRegistry registry;
    private LabState state;
    private LabFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        state = new LabState(events::add, registry, 0, 0);
        filter = new LabFilter(state);
    }

    private MockHttpServletResponse call(String path, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void byDefaultTheApiWorksWithoutLimits() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call("/api/auth/profile/Daniel", chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "La solicitud debe llegar al controlador");
        assertEquals(0, state.getActive(), "El cupo se libera al terminar");
    }

    @Test
    void faultRejectsApiButNotMetricsAndTogglesReadiness() throws Exception {
        state.update(false, null, null);

        MockHttpServletResponse api = call("/api/auth/profile/Daniel", new MockFilterChain());
        assertEquals(503, api.getStatus());

        MockFilterChain metricsChain = new MockFilterChain();
        MockHttpServletResponse metrics = call("/actuator/prometheus", metricsChain);
        assertEquals(200, metrics.getStatus(), "Las métricas siguen respondiendo durante la falla");
        assertNotNull(metricsChain.getRequest());

        state.update(true, null, null);
        List<Object> states = events.stream()
                .filter(e -> e instanceof AvailabilityChangeEvent<?>)
                .map(e -> ((AvailabilityChangeEvent<?>) e).getState())
                .map(Object.class::cast)
                .toList();
        assertEquals(List.of(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC), states);
    }

    @Test
    void capacityIsEnforcedAndReleased() {
        state.update(null, null, 2);

        assertTrue(state.tryAcquire());
        assertTrue(state.tryAcquire());
        assertFalse(state.tryAcquire(), "Tercer cupo rechazado con capacidad 2");
        assertEquals(2.0, registry.get("ecirace.lab.active.requests").gauge().value());

        state.release();
        assertTrue(state.tryAcquire(), "Al liberar un cupo se puede volver a entrar");
    }

    @Test
    void requestWithoutFreeSlotGets503() throws Exception {
        state.update(null, null, 1);
        assertTrue(state.tryAcquire()); // ocupa el único cupo

        MockHttpServletResponse response = call("/api/rooms", new MockFilterChain());
        assertEquals(503, response.getStatus());
        assertEquals(1, state.getActive(), "Un rechazo no debe liberar el cupo de otro");
    }

    @Test
    void invalidValuesAreRejectedWithoutChangingTheState() {
        state.update(true, 120, 8);

        assertThrows(IllegalArgumentException.class, () -> state.update(null, -1, null));
        assertThrows(IllegalArgumentException.class, () -> state.update(null, 1001, null));
        assertThrows(IllegalArgumentException.class, () -> state.update(null, 60, -3));

        assertEquals(120, state.getDelayMs());
        assertEquals(8, state.getCapacity());
    }

    @Test
    void readyEndpointFollowsTheInjectedFault() {
        LabReadyController controller = new LabReadyController(state);
        assertEquals(200, controller.ready().getStatusCode().value());

        state.update(false, null, null);
        assertEquals(503, controller.ready().getStatusCode().value());

        state.update(true, null, null);
        assertEquals(200, controller.ready().getStatusCode().value());
    }

    @Test
    void gaugesReflectTheConfiguration() {
        state.update(true, 60, 8);
        assertEquals(8.0, registry.get("ecirace.lab.capacity").gauge().value());
        assertEquals(60.0, registry.get("ecirace.lab.delay.ms").gauge().value());
        assertEquals(1.0, registry.get("ecirace.lab.ready").gauge().value());
    }
}
