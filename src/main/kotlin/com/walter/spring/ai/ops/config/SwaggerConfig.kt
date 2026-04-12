package com.walter.spring.ai.ops.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Spring AI Ops API")
                .description(
                    """
                    AI-powered operations automation tool.

                    - **Grafana Alerting** webhook → Loki log query → LLM error analysis
                    - **GitHub push** webhook → commit diff → LLM code review
                    - Results are pushed to the browser in real time via WebSocket (`/ws`)

                    **WebSocket topics**
                    | Topic | Payload | Trigger |
                    |---|---|---|
                    | `/topic/firing` | `AnalyzeFiringRecord` | LLM error analysis complete |
                    | `/topic/commit` | `CodeReviewRecord` | LLM code review complete |
                    """.trimIndent()
                )
                .version("1.0.0")
                .contact(
                    Contact()
                        .name("walter.hwang")
                        .url("https://github.com/lyvius2")
                )
                .license(
                    License()
                        .name("MIT License")
                        .url("https://opensource.org/licenses/MIT")
                )
        )
        .addServersItem(Server().url("/").description("Local"))
}

