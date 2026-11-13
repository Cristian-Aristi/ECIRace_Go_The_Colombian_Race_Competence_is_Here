package edu.eci.arsw.eciracego.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lee "Authorization: Bearer &lt;token&gt;" en /api/** y, si el token es válido, deja al usuario en la
 * petición ({@link AuthenticatedUser#from}). Un token inválido se trata como anónimo: cada endpoint
 * decide si exige sesión ({@link AuthenticatedUser#require}).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AuthTokenFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    public AuthTokenFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = TokenService.bearer(request.getHeader("Authorization"));
        if (token != null) {
            tokenService.authenticate(token).ifPresent(profile ->
                    request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser(profile.getUsername())));
        }
        chain.doFilter(request, response);
    }
}
