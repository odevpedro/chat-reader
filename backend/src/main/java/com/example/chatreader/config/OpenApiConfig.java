package com.example.chatreader.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentacao OpenAPI (Swagger UI em /swagger-ui.html). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatReaderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chat Reader API")
                        .version("v1")
                        .description("Catalogo, importacao e (em breve) sincronizacao de "
                                + "conversas com IA para leitura no Kindle.")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
