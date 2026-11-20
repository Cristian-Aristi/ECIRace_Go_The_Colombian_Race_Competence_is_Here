package edu.eci.arsw.eciracego;

import edu.eci.arsw.eciracego.security.RequestPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditoría Sprint 9 (2.ª ronda): el límite estricto de registros comparaba la ruta CRUDA, así que
 * "POST /api/auth/%67uest" llegaba al mismo endpoint sin límite. Ahora se compara la ruta decodificada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ecirace.ratelimit.enabled=true", "ecirace.google.client-id="})
class RateLimitBypassTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void encodedPathsShareTheStrictSignupLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(URI.create("/api/auth/%67uest")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post(URI.create("/api/rooms/%63reate")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post(URI.create("/api/auth/guest;x=1")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void requestPathsAreNormalized() {
        assertEquals("/api/auth/guest", RequestPaths.of(request("/api/auth/%67uest")));
        assertEquals("/api/auth/guest", RequestPaths.of(request("/api//auth/guest")));
        assertEquals("/api/auth/guest", RequestPaths.of(request("/api/auth/guest;jsessionid=1")));
        assertTrue(RequestPaths.isApi(request("/%61pi/rooms")));
        assertFalse(RequestPaths.isApi(request("/index.html")));
    }

    private static MockHttpServletRequest request(String rawUri) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", rawUri);
        r.setRequestURI(rawUri);
        return r;
    }
}
