package com.example.chatreader.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Emissao e validacao de tokens HS256.
 *
 * <p>Algoritmo escolhido porque e simetrico: o unico consumidor (o Kindle) apenas
 * <b>apresenta</b> o token, nunca o assina. Uma chave publica/private (RS256)
 * traria complexidade sem ganho.
 */
@Service
public class JwtService {

    public static final String CLAIM_USER = "chatreader.user";
    public static final String CLAIM_EXPIRATION = "chatreader.exp";

    private final SecretKey key;
    private final long expirationDays;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public JwtService(AuthProperties properties) {
        var secret = properties.jwtSecret();
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    "chatreader.auth.jwt-secret ausente ou curto demais (minimo 32 caracteres). "
                            + "Defina JWT_SECRET no ambiente; nunca commite o valor.");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.expirationDays = properties.expirationDays();
    }

    public String generate(String username) {
        var now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationDays, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    public TokenInfo parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new TokenInfo(
                    claims.get(CLAIM_USER, String.class),
                    claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("invalid_or_expired_token");
        }
    }

    public boolean matchesPassword(String raw, String hash) {
        if (hash == null || hash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    public String hashPassword(String raw) {
        return passwordEncoder.encode(raw);
    }

    public record TokenInfo(String username, Instant expiresAt) {
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
