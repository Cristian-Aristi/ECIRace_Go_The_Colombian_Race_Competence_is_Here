package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.lab.LabController;
import edu.eci.arsw.eciracego.lab.LabState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /admin/config ya no acepta el token por defecto "arsw-local" ni funciona sin token configurado. */
class LabControllerTokenTest {

    private static LabState state() {
        return new LabState(e -> { }, new SimpleMeterRegistry(), 0, 0);
    }

    @Test
    void withoutConfiguredTokenEverythingIsRejected() {
        LabController controller = new LabController(state(), "");
        assertEquals(403, controller.config("", Map.of("ready", false)).getStatusCode().value());
        assertEquals(403, controller.config("arsw-local", Map.of("ready", false)).getStatusCode().value());
        assertEquals(403, controller.config(null, Map.of("ready", false)).getStatusCode().value());
    }

    @Test
    void onlyTheConfiguredTokenIsAccepted() {
        LabState state = state();
        LabController controller = new LabController(state, "token-largo-de-laboratorio-123");
        assertEquals(403, controller.config("arsw-local", Map.of("ready", false)).getStatusCode().value());
        assertEquals(200, controller.config("token-largo-de-laboratorio-123", Map.of("delay_ms", 10)).getStatusCode().value());
        assertEquals(10, state.getDelayMs());
    }
}
