package com.team3.backend.global.config;

import com.team3.backend.global.role.ApiRole;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ApiRole
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        SecurityScheme cookieAuth = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("access_token");

        return new OpenAPI()
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "cookieAuth",
                                        cookieAuth
                                )
                );
    }
}