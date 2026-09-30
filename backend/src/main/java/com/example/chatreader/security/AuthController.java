package com.example.chatreader.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Login do usuario unico: recebe usuario+senha, devolve um JWT.
 *
 * <p>A senha e comparada contra o hash BCrypt vindo do ambiente. A senha em texto
 * puro e a senha comparada <b>nunca</b> entram no log (secao 26/31).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Autenticacao do usuario unico")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthProperties properties;
    private final JwtService jwtService;

    public AuthController(AuthProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @Operation(summary = "Autentica e retorna um token Bearer (JWT)")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (!properties.enabled()) {
            // modo dev/test: sem credenciais, emite token para qualquer usuario
            return new LoginResponse(jwtService.generate("dev"), Instant.now().plusSeconds(3600));
        }

        var userOk = safeEquals(request.username(), properties.username());
        var passOk = jwtService.matchesPassword(request.password(), properties.passwordHash());
        if (!(userOk && passOk)) {
            log.warn("Tentativa de login falhada para usuario '{}'", safeLog(request.username()));
            throw new BadCredentialsException();
        }

        var token = jwtService.generate(properties.username());
        log.info("Login concluido para usuario '{}'", properties.username());
        return new LoginResponse(token, Instant.now().plusSeconds(properties.expirationDays() * 86400L));
    }

    /** Comparacao em tempo constante para nao vazar informacao por tempo. */
    private boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String safeLog(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt) {
    }

    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() {
            super("Credenciais invalidas");
        }
    }
}
