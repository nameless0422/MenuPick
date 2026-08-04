package com.nameless0422.MenuPick.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /swagger-ui, /v3/api-docs 노출 여부는 springdoc.swagger-ui.enabled /
 * springdoc.api-docs.enabled로 제어한다 (기본 false, local 프로파일에서만 true).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI menuPickOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MenuPick API")
                        .description("메뉴·식당 추천 서비스 API. 에러 코드 카탈로그는 "
                                + "com.nameless0422.MenuPick.common.exception.ErrorCode 참고.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
