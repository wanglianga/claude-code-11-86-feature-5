package com.ccdev.transit.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transitPilotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("公交网络灰度实验服务 API")
                        .version("1.0.0")
                        .description("随机分配、暴露路径、共同支持、ITT/CACE 估计、安全护栏与扩围证据。"
                                + "可使用 HTTP Basic（analyst/analyst123 或 approver/approver123）调用接口。"))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
