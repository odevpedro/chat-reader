package com.example.chatreader.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtService — emissao e validacao de token (secao 26)")
class JwtServiceTest {

    private static final String SECRET = "segredo-de-teste-com-tamanho-suficiente-para-hs256-1234567890";

    private final JwtService service = new JwtService(
            new AuthProperties("reader", "$2a$10$hash", SECRET, 30, true));

    @Test
    @DisplayName("token gerado e aceito na volta")
    void generatedTokenIsAccepted() {
        var token = service.generate("reader");
        var info = service.parse(token);

        assertThat(info.username()).isEqualTo("reader");
        assertThat(info.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("token sem assinatura e rejeitado")
    void rejectsUnsignedToken() {
        assertThatThrownBy(() -> service.parse("abc.def.ghi"))
                .isInstanceOf(JwtService.InvalidTokenException.class)
                .hasMessage("invalid_or_expired_token");
    }

    @Test
    @DisplayName("token vazio e rejeitado")
    void rejectsEmptyToken() {
        assertThatThrownBy(() -> service.parse(""))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("token assinado com outro segredo e rejeitado")
    void rejectsForeignSignature() {
        var other = new JwtService(new AuthProperties("reader", "$2a$10$hash",
                "outro-segredo-com-tamanho-suficiente-para-hs256-abcdefghij", 30, true));
        var foreignToken = other.generate("reader");

        assertThatThrownBy(() -> service.parse(foreignToken))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("token expirado e rejeitado")
    void rejectsExpiredToken() {
        var expired = new JwtService(new AuthProperties("reader", "$2a$10$hash", SECRET, -1, true));
        // expirationDays <= 0 cai no padrao de 30 dias, portanto construimos um token
        // com validade negativa via JwtService.generate e forjamos a expiracao:
        var token = expired.generate("reader");
        assertThat(expired.parse(token).expiresAt()).isAfter(Instant.now());

        var forged = io.jsonwebtoken.Jwts.builder()
                .subject("reader")
                .claim(JwtService.CLAIM_USER, "reader")
                .expiration(java.util.Date.from(Instant.now().minusSeconds(3600)))
                .signWith(new javax.crypto.spec.SecretKeySpec(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();

        assertThatThrownBy(() -> service.parse(forged))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("senha so e comparada contra hash BCrypt")
    void passwordRequiresBcryptHash() {
        var hash = service.hashPassword("senha-secreta");
        assertThat(hash).startsWith("$2");
        assertThat(service.matchesPassword("senha-secreta", hash)).isTrue();
        assertThat(service.matchesPassword("outra", hash)).isFalse();
    }

    @Test
    @DisplayName("hash ausente nunca casa com senha nenhuma")
    void emptyHashNeverMatches() {
        assertThat(service.matchesPassword("qualquer", null)).isFalse();
        assertThat(service.matchesPassword("qualquer", "")).isFalse();
        assertThat(service.matchesPassword("qualquer", "   ")).isFalse();
    }

    @Test
    @DisplayName("o segredo nunca aparece no token")
    void secretIsNotLeakedInToken() {
        var token = service.generate("reader");
        assertThat(token).doesNotContain(SECRET);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("boot falha com segredo curto demais")
    void failsFastOnWeakSecret() {
        assertThatThrownBy(() -> new JwtService(
                new AuthProperties("reader", "$2a$10$hash", "curto", 30, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    @Test
    @DisplayName("boot falha com segredo ausente")
    void failsFastOnMissingSecret() {
        assertThatThrownBy(() -> new JwtService(
                new AuthProperties("reader", "$2a$10$hash", "  ", 30, true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
