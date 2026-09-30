package com.example.chatreader.config;

import com.example.chatreader.security.AuthProperties;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Validacao de configuracao no boot. Falha cedo e com mensagem clara quando uma
 * credencial obrigatoria nao foi definida (secao 45: sem credenciais hardcoded).
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityValidation securityValidation(AuthProperties properties) {
        if (!properties.enabled()) {
            log.warn("chatreader.auth.enabled=false — a API esta ABERTA. "
                    + "Use apenas em desenvolvimento.");
            return new SecurityValidation(false);
        }

        if (isBlank(properties.username())) {
            throw new IllegalStateException(
                    "CHAT_READER_USERNAME nao definido. Configure a variavel de ambiente.");
        }
        if (isBlank(properties.passwordHash())) {
            throw new IllegalStateException(
                    "CHAT_READER_PASSWORD_HASH nao definido. Gere com: "
                            + "htpasswd -bnBC 10 '' 'sua-senha' | tr -d ':\n'");
        }
        if (isBlank(properties.jwtSecret()) || properties.jwtSecret().length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET ausente ou com menos de 32 caracteres. Gere com: openssl rand -base64 48");
        }
        if (!properties.passwordHash().startsWith("$2")) {
            log.warn("CHAT_READER_PASSWORD_HASH nao parece um hash BCrypt (esperado prefixo $2).");
        }
        // valida que o segredo e forte o bastante para HS256
        Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        log.info("Autenticacao habilitada para o usuario unico '{}' (JWT {} dias).",
                properties.username(), properties.expirationDays());
        return new SecurityValidation(true);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Marcador de que a configuracao de seguranca foi validada no boot. */
    public record SecurityValidation(boolean enabled) {
    }
}
