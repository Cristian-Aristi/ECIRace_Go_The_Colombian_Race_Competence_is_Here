package edu.eci.arsw.eciracego;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import edu.eci.arsw.eciracego.config.StompSecurityInterceptor;
import edu.eci.arsw.eciracego.controller.AuthController;
import edu.eci.arsw.eciracego.dto.AuthRequest;
import edu.eci.arsw.eciracego.dto.SessionResponse;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.AuthenticatedUser;
import edu.eci.arsw.eciracego.security.GoogleIdentity;
import edu.eci.arsw.eciracego.security.GoogleTokenVerifier;
import edu.eci.arsw.eciracego.security.RateLimiter;
import edu.eci.arsw.eciracego.security.TokenService;
import edu.eci.arsw.eciracego.service.impl.AuthServiceImpl;
import edu.eci.arsw.eciracego.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** Registro e inicio de sesión, tokens de sesión y seguridad de los WebSockets. */
class SesionesYSeguridadTest {

    private static final String SECRETO = "un-secreto-de-pruebas-de-sesiones-que-tiene-mas-de-64-bytes-de-largo!!";

    private final Map<String, UserProfile> db = new HashMap<>();
    private UserProfileRepository repo;
    private AuthServiceImpl auth;
    private TokenService tokens;
    private RoomServiceImpl salas;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.save(any(UserProfile.class))).thenAnswer(inv -> {
            UserProfile p = inv.getArgument(0);
            db.put(p.getUsername(), p);
            return p;
        });
        Mockito.when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        Mockito.when(repo.existsByUsernameIgnoreCase(anyString())).thenAnswer(inv ->
                db.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(inv.getArgument(0))));
        Mockito.when(repo.findByGoogleSubject(anyString())).thenAnswer(inv -> db.values().stream()
                .filter(p -> inv.getArgument(0).equals(p.getGoogleSubject())).findFirst());
        Mockito.doAnswer(inv -> db.remove(((UserProfile) inv.getArgument(0)).getUsername())).when(repo).delete(any());
        Mockito.when(repo.findTop50ByOrderByWinsCountDescRacesCountDescUsernameAsc())
                .thenAnswer(inv -> List.copyOf(db.values()));
        auth = new AuthServiceImpl(repo);
        tokens = new TokenService(SECRETO, repo);
        salas = new RoomServiceImpl();
    }

    private static int status(Runnable accion) {
        return assertThrows(ApiException.class, accion::run).getStatus().value();
    }

    // ------------------------------------------------------------------ servicio de autenticación

    @Test
    void loginConGoogleRegistraYLuegoReconoceLaCuenta() {
        GoogleIdentity id = new GoogleIdentity("sub-1", "ana@mail.com", "Ana María", "Ana",
                "https://lh3.googleusercontent.com/foto.png");
        ApiException sinTag = assertThrows(ApiException.class, () -> auth.loginWithGoogle(id, "  "));
        assertEquals("USERNAME_REQUIRED", sinTag.getCode());
        assertEquals("USERNAME_REQUIRED", assertThrows(ApiException.class, () -> auth.loginWithGoogle(id, null)).getCode());

        UserProfile ana = auth.loginWithGoogle(id, "AnaRacer");
        assertFalse(ana.isGuest());
        assertEquals("https://lh3.googleusercontent.com/foto.png", ana.getPictureUrl());

        // Segunda vez: misma cuenta de Google, datos frescos y foto no confiable descartada
        ana.setSessionKey(null);
        UserProfile otra = auth.loginWithGoogle(new GoogleIdentity("sub-1", "nuevo@mail.com", "Ana", "Ana",
                "http://evil.com/x.png"), null);
        assertEquals("AnaRacer", otra.getUsername());
        assertEquals("nuevo@mail.com", otra.getEmail());
        assertEquals("", otra.getPictureUrl());
        assertNotNull(otra.getSessionKey());

        // Fotos inválidas
        assertEquals("", auth.loginWithGoogle(new GoogleIdentity("sub-2", null, "B", "B", null), "Beto").getPictureUrl());
        assertEquals("", auth.loginWithGoogle(new GoogleIdentity("sub-3", null, "C", "C", "https://[malformada"), "Caro").getPictureUrl());
        assertEquals("", auth.loginWithGoogle(new GoogleIdentity("sub-4", null, "D", "D", "https://x.googleusercontent.com/" + "a".repeat(600)), "Dani").getPictureUrl());
        assertEquals("", auth.loginWithGoogle(new GoogleIdentity("sub-5", null, "E", "E", "https:sin-host"), "Eva").getPictureUrl());
        assertEquals(409, status(() -> auth.loginWithGoogle(new GoogleIdentity("sub-6", null, "X", "X", null), "anaracer")));
    }

    @Test
    void invitadosConTagPropioOAleatorio() {
        assertTrue(auth.registerGuest(null).getUsername().startsWith("Piloto_"));
        assertTrue(auth.registerGuest("   ").getUsername().startsWith("Piloto_"));
        assertEquals("Rayo", auth.registerGuest("Rayo").getUsername());
        assertEquals(409, status(() -> auth.registerGuest("RAYO")));
        assertEquals(400, status(() -> auth.registerGuest("a b")));

        // Si todos los nombres aleatorios están ocupados, se pide un tag propio
        Mockito.when(repo.existsByUsernameIgnoreCase(anyString())).thenReturn(true);
        assertEquals(503, status(() -> auth.registerGuest(null)));
    }

    @Test
    void perfilCambioDeTagCierreDeSesionYClasificacion() {
        UserProfile rayo = auth.registerGuest("Rayo");
        auth.registerGuest("Trueno");
        assertTrue(auth.getProfile(" Rayo ").isPresent());
        assertTrue(auth.getProfile(null).isEmpty());
        assertTrue(auth.getProfile("  ").isEmpty());
        assertTrue(auth.getProfile("x".repeat(70)).isEmpty());
        assertTrue(auth.exists("rayo"));
        assertFalse(auth.exists(null));

        assertSame(rayo, auth.updateUsername("Rayo", "Rayo"));
        assertEquals(409, status(() -> auth.updateUsername("Rayo", "trueno")));
        assertEquals(401, status(() -> auth.updateUsername("Nadie", "Nuevo")));
        UserProfile renombrado = auth.updateUsername("Rayo", "RAYO");
        assertEquals("RAYO", renombrado.getUsername());

        String claveAntes = renombrado.getSessionKey();
        auth.logout("RAYO");
        assertNotEquals(claveAntes, db.get("RAYO").getSessionKey());
        auth.logout("Nadie");

        assertEquals(1, auth.getLeaderboard(1).size());
        assertEquals(2, auth.getLeaderboard(0).size() + 1);
        assertEquals(2, auth.getLeaderboard(500).size());
    }

    // ------------------------------------------------------------------ controlador /api/auth

    @Test
    void apiDeAutenticacion() {
        GoogleTokenVerifier google = Mockito.mock(GoogleTokenVerifier.class);
        Mockito.when(google.isEnabled()).thenReturn(true);
        Mockito.when(google.getClientId()).thenReturn("cliente.apps.googleusercontent.com");
        Mockito.when(google.verify("token-google")).thenReturn(
                new GoogleIdentity("g-1", "g@mail.com", "Gabi", "Gabi", null));
        AuthController api = new AuthController(auth, tokens, google, salas, true);

        assertEquals(Boolean.TRUE, api.config().get("demoTools"));
        assertEquals(400, status(() -> api.loginWithGoogle(null)));
        SessionResponse g = api.loginWithGoogle(new AuthRequest("token-google", "Gabi"));
        assertNotNull(g.token());

        assertEquals(201, api.loginGuest(null).getStatusCode().value());
        assertEquals(201, api.loginGuest(new AuthRequest(null, "Invitado1")).getStatusCode().value());

        MockHttpServletRequest gabi = new MockHttpServletRequest();
        gabi.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser("Gabi"));
        assertEquals("Gabi", api.me(gabi).username());
        assertEquals(401, status(() -> api.me(new MockHttpServletRequest())));
        MockHttpServletRequest fantasma = new MockHttpServletRequest();
        fantasma.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser("Fantasma"));
        assertEquals(401, status(() -> api.me(fantasma)));

        assertEquals(200, api.getProfile("Gabi").getStatusCode().value());
        assertEquals(404, api.getProfile("Nadie").getStatusCode().value());
        assertEquals(3, api.leaderboard(10).size());

        // No se puede cambiar el tag estando en una sala de espera
        salas.createRoom(null, "ESPERA");
        salas.registerRunnerInRoom("ESPERA", "Gabi");
        assertEquals(409, status(() -> api.updateUsername(Map.of("newUsername", "Gabriela"), gabi)));
        salas.removeRunnerFromRoom("ESPERA", "Gabi");
        assertEquals("Gabriela", api.updateUsername(Map.of("newUsername", "Gabriela"), gabi).profile().username());
        assertEquals(400, status(() -> api.updateUsername(null, fantasma)));

        MockHttpServletRequest gabriela = new MockHttpServletRequest();
        gabriela.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser("Gabriela"));
        assertEquals(204, api.logout(gabriela).getStatusCode().value());
    }

    // ------------------------------------------------------------------ tokens de sesión

    private String firmar(JWSAlgorithm alg, String secreto, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
        jwt.sign(new MACSigner(secreto.getBytes()));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder claims(String sub, String sk) {
        return new JWTClaimsSet.Builder().issuer("ecirace-go").subject(sub).claim("sk", sk)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void tokensDeSesionAlteradosVencidosOIncompletosNoSirven() throws Exception {
        UserProfile ana = auth.registerGuest("Ana");
        String sk = ana.getSessionKey();
        assertTrue(tokens.authenticate(tokens.issue(ana)).isPresent());

        assertTrue(tokens.authenticate(null).isEmpty());
        assertTrue(tokens.authenticate("  ").isEmpty());
        assertTrue(tokens.authenticate("x".repeat(5000)).isEmpty());
        assertTrue(tokens.authenticate("no.es.jwt").isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS512, SECRETO, claims("Ana", sk).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, "otro-secreto-distinto-de-al-menos-32-bytes!!", claims("Ana", sk).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", sk).issuer("otro").build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", sk).expirationTime(null).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", sk).expirationTime(new Date(1000)).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims(null, sk).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", null).build())).isEmpty());
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", "otra-clave").build())).isEmpty());
        ana.setSessionKey(null);
        assertTrue(tokens.authenticate(firmar(JWSAlgorithm.HS256, SECRETO, claims("Ana", sk).build())).isEmpty());
        assertThrows(IllegalStateException.class, () -> tokens.issue(ana));

        assertNull(TokenService.bearer(null));
        assertNull(TokenService.bearer("Basic abc"));
        assertEquals("abc", TokenService.bearer("  bearer abc "));

        assertThrows(IllegalStateException.class, () -> new TokenService("corto", repo));
        assertNotNull(new TokenService("  ", repo));
        assertNotNull(new TokenService(null, repo));
    }

    // ------------------------------------------------------------------ STOMP

    private StompSecurityInterceptor stomp() {
        return new StompSecurityInterceptor(tokens, salas, new RateLimiter());
    }

    private static Message<byte[]> frame(StompCommand cmd, String destino, String user, boolean conAtributos, String... headers) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(cmd);
        acc.setSessionId("s1");
        acc.setSubscriptionId("sub-" + (destino == null ? "x" : destino.hashCode()));
        if (conAtributos) acc.setSessionAttributes(new HashMap<>());
        if (destino != null) acc.setDestination(destino);
        if (user != null) acc.setUser(new AuthenticatedUser(user));
        for (int i = 0; i + 1 < headers.length; i += 2) acc.addNativeHeader(headers[i], headers[i + 1]);
        acc.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }

    @Test
    void stompComandosDeControlYLimiteDeSuscripciones() {
        StompSecurityInterceptor s = stomp();
        salas.createRoom(null, "SALA-WS");
        salas.registerRunnerInRoom("SALA-WS", "Ana");

        // Mensajes sin cabeceras STOMP (latidos internos) pasan sin cambios
        Message<String> interno = MessageBuilder.withPayload("ping").build();
        assertSame(interno, s.preSend(interno, null));

        // Conexión anónima y comando STOMP equivalente a CONNECT
        assertNotNull(s.preSend(frame(StompCommand.CONNECT, null, null, true), null));
        UserProfile ana = auth.registerGuest("Ana");
        Message<byte[]> stompCmd = frame(StompCommand.STOMP, null, null, true, "Authorization", "Bearer " + tokens.issue(ana));
        s.preSend(stompCmd, null);
        assertEquals("Ana", StompHeaderAccessor.wrap(stompCmd).getUser().getName());

        // Suscripción sin destino o sin atributos de sesión
        assertThrows(MessageDeliveryException.class, () -> s.preSend(frame(StompCommand.SUBSCRIBE, null, "Ana", true), null));
        assertNotNull(s.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS", "Ana", false), null));

        // Más de 10 suscripciones en la misma sesión
        Map<String, Object> attrs = new HashMap<>();
        MessageDeliveryException demasiadas = assertThrows(MessageDeliveryException.class, () -> {
            for (int i = 0; i < 12; i++) {
                StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
                acc.setSessionId("s2");
                acc.setSubscriptionId("n" + i);
                acc.setSessionAttributes(attrs);
                acc.setDestination("/topic/rooms/SALA-WS/status");
                acc.setUser(new AuthenticatedUser("Ana"));
                s.preSend(MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders()), null);
            }
        });
        assertTrue(demasiadas.getMessage().contains("Demasiadas"));

        // Enviar a un destino que no es de la app o sin destino
        assertThrows(MessageDeliveryException.class, () -> s.preSend(frame(StompCommand.SEND, null, "Ana", true), null));

        // Cancelar suscripciones (conocidas y desconocidas), ACK y desconexión
        assertNotNull(s.preSend(frame(StompCommand.UNSUBSCRIBE, "/topic/rooms/SALA-WS", "Ana", true), null));
        StompHeaderAccessor otra = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        otra.setSessionId("sin-suscripciones");
        otra.setSubscriptionId("z");
        assertNotNull(s.preSend(MessageBuilder.createMessage(new byte[0], otra.getMessageHeaders()), null));
        assertNotNull(s.preSend(frame(StompCommand.ACK, null, "Ana", true), null));
        assertNotNull(s.preSend(frame(StompCommand.DISCONNECT, null, "Ana", true), null));
        s.forgetSession(null);
    }

    @Test
    void stompSalidaSoloFiltraMensajesDeSalas() {
        StompSecurityInterceptor s = stomp();
        SimpMessageHeaderAccessor ack = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        Message<byte[]> m1 = MessageBuilder.createMessage(new byte[0], ack.getMessageHeaders());
        assertSame(m1, s.outboundGuard().preSend(m1, null));

        SimpMessageHeaderAccessor sinDestino = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        Message<byte[]> m2 = MessageBuilder.createMessage(new byte[0], sinDestino.getMessageHeaders());
        assertSame(m2, s.outboundGuard().preSend(m2, null));

        // Sesión con suscripciones, pero no a esta
        salas.createRoom(null, "SALA-WS");
        salas.registerRunnerInRoom("SALA-WS", "Ana");
        s.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms/SALA-WS", "Ana", true), null);
        SimpMessageHeaderAccessor otraSub = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        otraSub.setSessionId("s1");
        otraSub.setSubscriptionId("otra");
        otraSub.setDestination("/topic/rooms/SALA-WS");
        assertNull(s.outboundGuard().preSend(MessageBuilder.createMessage(new byte[0], otraSub.getMessageHeaders()), null));
    }
}
