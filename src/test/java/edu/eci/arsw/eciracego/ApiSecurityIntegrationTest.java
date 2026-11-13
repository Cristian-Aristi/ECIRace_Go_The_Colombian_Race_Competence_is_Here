package edu.eci.arsw.eciracego;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recorre la API completa como lo haría un atacante y como lo hace el juego. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ecirace.ratelimit.enabled=false", "ecirace.google.client-id="})
class ApiSecurityIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    private JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private String guest(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(r).get("token").asString();
    }

    @Test
    void fullRaceFlowWithAuthorization() throws Exception {
        String host = guest("HostIT");
        String runner = guest("RunnerIT");
        String intruder = guest("IntrusoIT");

        // Un tag existente no se puede reclamar (antes devolvía el perfil y su correo)
        mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"hostit\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        MvcResult created = mvc.perform(post("/api/rooms/create").header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"preferredCode\":\"IT-SALA\"}"))
                .andExpect(status().isCreated()).andReturn();
        String hostKey = json(created).get("hostKey").asString();
        assertFalse(json(created).get("room").has("hostKey"), "La clave del host no viaja dentro de la sala");

        mvc.perform(post("/api/rooms/IT-SALA/join")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/rooms/IT-SALA/join").header("Authorization", "Bearer " + runner)).andExpect(status().isOk());

        // La lista pública no expone roster ni host
        mvc.perform(get("/api/rooms")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("RunnerIT"))))
                .andExpect(content().string(not(containsString("hostName"))));

        // Extraños no ven la sala ni las posiciones
        mvc.perform(get("/api/rooms/IT-SALA")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rooms/IT-SALA").header("Authorization", "Bearer " + intruder)).andExpect(status().isForbidden());
        mvc.perform(get("/api/rooms/IT-SALA/positions").header("Authorization", "Bearer " + intruder)).andExpect(status().isForbidden());
        mvc.perform(get("/api/rooms/IT-SALA").header("Authorization", "Bearer " + runner)).andExpect(status().isOk());
        mvc.perform(get("/api/rooms/IT-SALA").header("X-Host-Key", hostKey)).andExpect(status().isOk());

        // Solo el host inicia: el hostName público ya no sirve para nada
        mvc.perform(post("/api/rooms/IT-SALA/start").header("Authorization", "Bearer " + runner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hostName\":\"HostIT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/rooms/IT-SALA/meta").header("X-Host-Key", hostKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":\"NaN\",\"lon\":-74.04}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/rooms/IT-SALA/meta").header("X-Host-Key", hostKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":4.7801,\"lon\":-74.0401}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metaLat").value(4.7801));
        mvc.perform(post("/api/rooms/IT-SALA/start").header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"metaLat\":\"abc\",\"metaLon\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/IT-SALA/start").header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"metaLat\":4.78,\"metaLon\":-74.04}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void profilesNeverExposeEmailAndWritesNeedSession() throws Exception {
        String token = guest("PerfilIT");

        mvc.perform(get("/api/auth/profile/PerfilIT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/auth/leaderboard")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("email"))));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("PerfilIT"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        // Renombrar a otro sin sesión: imposible
        mvc.perform(put("/api/auth/username").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentUsername\":\"PerfilIT\",\"newUsername\":\"Robado\"}"))
                .andExpect(status().isUnauthorized());

        // El endpoint para inflar victorias ya no existe
        int code = mvc.perform(post("/api/auth/race-result").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"PerfilIT\",\"won\":true}")).andReturn().getResponse().getStatus();
        assertEquals(true, code == 404 || code == 405, "race-result respondió " + code);

        // Renombrarse a sí mismo invalida el token anterior
        MvcResult renamed = mvc.perform(put("/api/auth/username").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newUsername\":\"PerfilIT2\"}"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + json(renamed).get("token").asString()))
                .andExpect(jsonPath("$.username").value("PerfilIT2"));
    }

    @Test
    void googleLoginIsVerifiedOnTheServer() throws Exception {
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.googleEnabled").value(false));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"simulated-google-token-1\",\"username\":\"Falso\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self' https://accounts.google.com/gsi/client")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/rooms")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void corsIsNotOpenToEveryOrigin() throws Exception {
        mvc.perform(get("/api/rooms").header("Origin", "https://evil.example.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
