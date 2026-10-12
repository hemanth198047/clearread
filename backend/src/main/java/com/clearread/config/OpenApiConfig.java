package com.clearread.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI clearReadOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ClearRead API")
                        .description(
                                "ClearRead v1.0 — turn text-based files into structured reading experiences. "
                                        + "Capability registry (`GET /v1/capabilities`) is the source of truth for format support."
                        )
                        .version("1.0.3")
                        .license(new License().name("Apache-2.0")));
    }
}
