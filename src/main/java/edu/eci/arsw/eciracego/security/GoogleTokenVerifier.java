package edu.eci.arsw.eciracego.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Set;

/**
 * Verifica en el SERVIDOR el ID token que entrega Google Identity Services:
 * firma RS256 con las llaves públicas de Google (JWKS, con caché), emisor accounts.google.com,
 * audiencia = nuestro client ID, expiración y correo verificado.
 * Nada de lo que el navegador diga sobre el usuario (email, nombre, foto) se usa sin pasar por aquí.
 */
@Component
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);
    public static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private final DefaultJWTProcessor<SecurityContext> processor;

    @Autowired
    public GoogleTokenVerifier(@Value("${ecirace.google.client-id:}") String clientId) throws MalformedURLException {
        this(clientId, JWKSourceBuilder.create(URI.create(GOOGLE_JWKS).toURL()).retrying(true).build());
    }

    public GoogleTokenVerifier(String clientId, JWKSource<SecurityContext> keySource) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
        this.processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                this.clientId, null, Set.of("sub", "iss", "aud", "exp", "iat", "email")));
        if (!isEnabled()) {
            log.warn("GOOGLE_CLIENT_ID no está definido: el inicio de sesión con Google queda desactivado.");
        }
    }

    public boolean isEnabled() {
        return !clientId.isEmpty();
    }

    public String getClientId() {
        return clientId;
    }

    public GoogleIdentity verify(String idToken) {
        if (!isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_DISABLED",
                    "El inicio de sesión con Google no está configurado en este servidor.");
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
            throw invalid();
        }
        try {
            JWTClaimsSet claims = processor.process(idToken.trim(), null);
            if (!ISSUERS.contains(claims.getIssuer())) {
                throw invalid();
            }
            Object verified = claims.getClaim("email_verified");
            if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Tu correo de Google no está verificado.");
            }
            return new GoogleIdentity(
                    claims.getSubject(),
                    claims.getStringClaim("email"),
                    claims.getStringClaim("name"),
                    claims.getStringClaim("given_name"),
                    claims.getStringClaim("picture"));
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.info("ID token de Google rechazado: {}", e.getMessage());
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID",
                "No se pudo verificar tu cuenta de Google. Inténtalo de nuevo.");
    }
}
