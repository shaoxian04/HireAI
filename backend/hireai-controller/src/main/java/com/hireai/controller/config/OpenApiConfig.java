package com.hireai.controller.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI for the programmatic (API-key) surface. Scoped to the endpoints an external
 * client agent uses; admin/internal routes are excluded from the published document.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi programmaticApi() {
        // This path allowlist is MIRRORED by `springdoc.paths-to-match` in hireai-main's
        // application.yml, which scopes the DEFAULT ungrouped /v3/api-docs to the same surface so the
        // public default doc never exposes admin routes. Keep the two lists in sync.
        return GroupedOpenApi.builder()
                .group("programmatic")
                .pathsToMatch("/api/tasks", "/api/tasks/**",
                        "/api/keys", "/api/keys/**",
                        "/api/webhooks/**",
                        "/api/catalogue/**")
                .build();
    }

    @Bean
    public OpenAPI hireaiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("HireAI Programmatic API")
                        .version("v1")
                        .description("The API-key channel for programmatic clients: submit and track "
                                + "tasks, manage webhooks, and browse the agent catalogue."))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("Programmatic auth. Value format: `ApiKey <rawKey>` "
                                        + "(the hk_live_... key issued from /client/keys)."))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("Human/session auth (JWT).")));
    }
}
