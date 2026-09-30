package com.example.chatreader.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base dos testes de integracao: Postgres real via Testcontainers (secao 30 —
 * "nao substituir todos os testes de banco por mocks").
 *
 * <p>O container e um singleton iniciado <b>uma vez por JVM</b>. Nao usamos as
 * anotacoes {@code @Testcontainers}/{@code @Container} de proposito: elas param o
 * container ao fim de cada classe, e como o Spring cacheia os contextos, a proxima
 * classe reutilizava a URL de um container ja parado (connection refused).
 * O Ryuk remove o container quando a JVM termina.
 *
 * <p>A auth fica desligada por {@code @TestPropertySource}; testes que precisam de
 * auth ligada sobrescrevem via {@code @DynamicPropertySource}, que tem precedencia
 * maior (evita duas fontes dinamicas disputando a mesma chave).
 */
@TestPropertySource(properties = {
        "chatreader.auth.enabled=false",
        // valores apenas de teste: o JwtService valida o segredo no construtor
        "chatreader.auth.jwt-secret=test-secret-nao-use-em-producao-0123456789abcdef",
        "chatreader.auth.username=test",
        "chatreader.auth.password-hash=$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
})
public abstract class AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("chatreader_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Identificador unico por teste, para isolar chats criados. */
    protected static String uniqueId() {
        return "test-" + COUNTER.incrementAndGet();
    }
}
