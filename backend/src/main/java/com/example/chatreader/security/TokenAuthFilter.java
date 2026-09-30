package com.example.chatreader.security;

import com.example.chatreader.security.JwtService.TokenInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Autenticacao por token Bearer (JWT HS256).
 *
 * <p>Deliberadamente <b>nao</b> usa o Spring Security completo: um filtro e um
 * controller de login sao suficientes para o MVP de usuario unico, e evitam a
 * configuracao de cadeia de filtros (secao 41: nada de complexidade desnecessaria).
 *
 * <p>Nunca loga o token, o header Authorization ou a senha (secao 31).
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;

    public TokenAuthFilter(JwtService jwtService, AuthProperties properties, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled() || isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }

        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            unauthorized(response, request, "missing_token");
            return;
        }

        var token = header.substring(BEARER.length()).strip();
        try {
            var claims = jwtService.parse(token);
            request.setAttribute(JwtService.CLAIM_USER, claims.username());
            request.setAttribute(JwtService.CLAIM_EXPIRATION, claims.expiresAt());
            chain.doFilter(request, response);
        } catch (JwtService.InvalidTokenException e) {
            // motivo (expirado/invalido) e util; o token em si nunca e logado
            log.debug("Token rejeitado em {}: {}", request.getRequestURI(), e.getMessage());
            unauthorized(response, request, e.getMessage());
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/api/auth/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/info")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/");
    }

    private void unauthorized(HttpServletResponse response, HttpServletRequest request, String reason)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "status", 401,
                "error", "Unauthorized",
                "reason", reason,
                "path", request.getRequestURI()));
    }
}
