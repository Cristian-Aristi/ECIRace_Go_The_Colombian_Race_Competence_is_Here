package edu.eci.arsw.eciracego;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import edu.eci.arsw.eciracego.security.ApiException;
import edu.eci.arsw.eciracego.security.GoogleIdentity;
import edu.eci.arsw.eciracego.security.GoogleTokenVerifier;
import edu.eci.arsw.eciracego.security.TokenService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SecurityTokensTest {

    private static final String CLIENT_ID = "123-abc.apps.googleusercontent.com";
    private static RSAKey googleKey;
    private static RSAKey otherKey;

    @BeforeAll
    static void keys() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
    }

    // ------------------------------------------------------------- tokens de sesión propios

    private TokenService tokens(UserProfile... profiles) {
        UserProfileRepository repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.findById(Mockito.anyString())).thenReturn(Optional.empty());
        for (UserProfile p : profiles) {
            Mockito.when(repo.findById(p.getUsername())).thenReturn(Optional.of(p));
        }
        return new TokenService("un-secreto-de-pruebas-de-al-menos-32-bytes!!", repo);
    }

    @Test
    void sessionTokenRoundTrip() {
        UserProfile p = new UserProfile("Daniel", null, "Daniel", "");
        p.rotateSessionKey();
        TokenService service = tokens(p);
        String token = service.issue(p);
        assertEquals("Daniel", service.authenticate(token).orElseThrow().getUsername());
        assertEquals(token, TokenService.bearer("Bearer " + token));
    }

    @Test
    void rotatedSessionKeyInvalidatesOldTokens() {
        UserProfile p = new UserProfile("Daniel", null, "Daniel", "");
        p.rotateSessionKey();
        TokenService service = tokens(p);
        String token = service.issue(p);
        p.rotateSessionKey();
        assertTrue(service.authenticate(token).isEmpty());
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        UserProfile p = new UserProfile("Daniel", null, "Daniel", "");
        p.rotateSessionKey();
        TokenService service = tokens(p);
        String token = service.issue(p);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        assertTrue(service.authenticate(tampered).isEmpty());
        assertTrue(service.authenticate("no-es-un-jwt").isEmpty());
        assertTrue(service.authenticate(null).isEmpty());

        UserProfileRepository repo = Mockito.mock(UserProfileRepository.class);
        Mockito.when(repo.findById("Daniel")).thenReturn(Optional.of(p));
        TokenService otherSecret = new TokenService("otro-secreto-distinto-de-al-menos-32-bytes", repo);
        assertTrue(otherSecret.authenticate(token).isEmpty(), "Firmado con otro secreto");
    }

    @Test
    void shortSecretIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new TokenService("corto", Mockito.mock(UserProfileRepository.class)));
    }

    // ------------------------------------------------------------- ID token de Google

    private static String googleToken(RSAKey key, String iss, String aud, Date exp, Object emailVerified) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss).audience(aud).subject("1234567890")
                .issueTime(new Date()).expirationTime(exp)
                .claim("email", "piloto@gmail.com").claim("email_verified", emailVerified)
                .claim("name", "Piloto Uno").claim("given_name", "Piloto")
                .claim("picture", "https://lh3.googleusercontent.com/a/abc")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static GoogleTokenVerifier verifier(String clientId) {
        return new GoogleTokenVerifier(clientId, new ImmutableJWKSet<SecurityContext>(new JWKSet(googleKey.toPublicJWK())));
    }

    private static Date inOneHour() {
        return new Date(System.currentTimeMillis() + 3_600_000);
    }

    @Test
    void validGoogleTokenIsAccepted() throws Exception {
        GoogleIdentity id = verifier(CLIENT_ID).verify(
                googleToken(googleKey, "https://accounts.google.com", CLIENT_ID, inOneHour(), true));
        assertEquals("1234567890", id.subject());
        assertEquals("piloto@gmail.com", id.email());
    }

    @Test
    void googleTokenWithWrongAudienceIssuerSignatureOrExpiryIsRejected() throws Exception {
        GoogleTokenVerifier v = verifier(CLIENT_ID);
        assertThrows(ApiException.class, () -> v.verify(
                googleToken(googleKey, "https://accounts.google.com", "otra-app.apps.googleusercontent.com", inOneHour(), true)));
        assertThrows(ApiException.class, () -> v.verify(
                googleToken(googleKey, "https://evil.example.com", CLIENT_ID, inOneHour(), true)));
        assertThrows(ApiException.class, () -> v.verify(
                googleToken(otherKey, "https://accounts.google.com", CLIENT_ID, inOneHour(), true)));
        assertThrows(ApiException.class, () -> v.verify(
                googleToken(googleKey, "accounts.google.com", CLIENT_ID, new Date(System.currentTimeMillis() - 600_000), true)));
        assertThrows(ApiException.class, () -> v.verify("simulated-google-token-123"));
        ApiException unverified = assertThrows(ApiException.class, () -> v.verify(
                googleToken(googleKey, "accounts.google.com", CLIENT_ID, inOneHour(), false)));
        assertEquals("EMAIL_NOT_VERIFIED", unverified.getCode());
    }

    @Test
    void googleLoginIsDisabledWithoutClientId() throws Exception {
        GoogleTokenVerifier v = verifier("");
        assertFalse(v.isEnabled());
        ApiException e = assertThrows(ApiException.class, () -> v.verify(
                googleToken(googleKey, "accounts.google.com", CLIENT_ID, inOneHour(), true)));
        assertEquals("GOOGLE_DISABLED", e.getCode());
    }
}
