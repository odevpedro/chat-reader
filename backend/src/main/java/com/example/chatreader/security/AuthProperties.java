package com.example.chatreader.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao de autenticacao do usuario unico do MVP.
 *
 * <p>Credenciais vem <b>sempre</b> do ambiente (variáveis de ambiente / .env),
 * nunca do codigo nem do repositorio (secao 26, 35).
 *
 * @param username        usuario unico do MVP
 * @param passwordHash    hash BCrypt da senha
 * @param jwtSecret       segredo HS256
 * @param expirationDays  validade do token em dias
 * @param enabled         quando false, a API fica aberta (apenas dev/test)
 */
@ConfigurationProperties(prefix = "chatreader.auth")
public record AuthProperties(
        String username,
        String passwordHash,
        String jwtSecret,
        long expirationDays,
        boolean enabled
) {
    public AuthProperties {
        expirationDays = expirationDays <= 0 ? 30 : expirationDays;
    }
}
