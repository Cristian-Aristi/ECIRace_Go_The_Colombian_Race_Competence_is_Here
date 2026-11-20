package edu.eci.arsw.eciracego.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import edu.eci.arsw.eciracego.model.UserProfile;
import edu.eci.arsw.eciracego.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * Tokens de sesión propios de ECIRace (JWT HS256).
 * <ul>
 *   <li>sub = username, sk = clave de sesión del perfil, exp = 7 días.</li>
 *   <li>Al validar, el "sk" debe coincidir con el guardado en la base de datos: cerrar sesión o
 *       cambiar el tag rota esa clave e invalida todos los tokens anteriores del piloto.</li>
 *   <li>El secreto llega por ECIRACE_TOKEN_SECRET. Si no se define, se genera uno aleatorio al
 *       arrancar (las sesiones se pierden al reiniciar el servidor, pero nunca hay un secreto por defecto).</li>
 * </ul>
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    public static final Duration TTL = Duration.ofDays(7);
    private static final String ISSUER = "ecirace-go";
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final UserProfileRepository repository;

    public TokenService(@Value("${ecirace.auth.token-secret:}") String configuredSecret,
                        UserProfileRepository repository) {
        this.repository = repository;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            this.secret = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(this.secret);
            log.warn("ECIRACE_TOKEN_SECRET no está definido: se usa un secreto aleatorio y las sesiones se pierden al reiniciar.");
        } else {
            byte[] bytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("ECIRACE_TOKEN_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes.");
            }
            this.secret = bytes;
        }
    }

    public String issue(UserProfile profile) {
        if (profile.getSessionKey() == null) {
            throw new IllegalStateException("El perfil no tiene clave de sesión");
        }
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(profile.getUsername())
                .claim("sk", profile.getSessionKey())
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + TTL.toMillis()))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token de sesión", e);
        }
    }

    /** Valida firma, emisor, expiración y clave de sesión. Devuelve el perfil si todo es correcto. */
    public Optional<UserProfile> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token.trim());
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            String subject = claims.getSubject();
            String sessionKey = claims.getStringClaim("sk");
            if (!ISSUER.equals(claims.getIssuer()) || exp == null || exp.before(new Date())
                    || subject == null || sessionKey == null) {
                return Optional.empty();
            }
            return repository.findById(subject)
                    .filter(p -> p.getSessionKey() != null && MessageDigest.isEqual(
                            p.getSessionKey().getBytes(StandardCharsets.UTF_8),
                            sessionKey.getBytes(StandardCharsets.UTF_8)));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    /** Extrae el token de un valor "Bearer xxx". */
    public static String bearer(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String v = headerValue.trim();
        return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : null;
    }
}
