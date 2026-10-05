package com.example.monitoring.common.config;

import com.example.monitoring.common.api.ApiErrorResponse;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Live DBMS AI API",
        version = "v1",
        description = "API contract for the DB monitoring system. Protected endpoints require a Bearer access token."
))
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    /**
     * Springdoc otherwise copies each method's success body into its documented error responses and
     * advertises bodies as any media type. Applies the common REST contract (docs/api.md 1절) to A·B·C paths:
     * 4xx/5xx use ApiErrorResponse, 204 has no body, and success bodies are application/json.
     */
    @Bean
    OpenApiCustomizer partBErrorResponses() {
        return openApi -> {
            Schema<?> errorSchema = SpringDocAnnotationsUtils.extractSchema(
                    openApi.getComponents(), ApiErrorResponse.class, null, null, openApi.getSpecVersion());
            openApi.getPaths().forEach((path, item) -> {
                if (!isPartBPath(path) && !isPartAPath(path) && !isPartCPath(path)) return;
                item.readOperations().forEach(operation -> operation.getResponses().forEach((code, response) -> {
                    if (code.startsWith("4") || code.startsWith("5")) {
                        response.setContent(new Content().addMediaType("application/json",
                                new MediaType().schema(errorSchema)));
                    } else if (code.equals("204")) {
                        response.setContent(null);
                    } else if ((isPartAPath(path) || isPartCPath(path)) && response.getContent() != null
                            && response.getContent().containsKey("*/*")) {
                        response.setContent(new Content().addMediaType("application/json",
                                response.getContent().get("*/*")));
                    }
                }));
            });
        };
    }

    private boolean isPartAPath(String path) {
        return path.startsWith("/api/v1/metrics/") || path.endsWith("/ping");
    }

    private boolean isPartBPath(String path) {
        return path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users")
                || path.startsWith("/api/v1/users/") || path.equals("/api/v1/databases")
                || path.startsWith("/api/v1/databases/") || path.equals("/api/v1/audit-logs")
                || path.equals("/api/v1/access-logs");
    }

    private boolean isPartCPath(String path) {
        return path.equals("/api/v1/databases/{id}/status")
                || path.equals("/api/v1/databases/{id}/risk-policy")
                || path.equals("/api/v1/incidents") || path.startsWith("/api/v1/incidents/")
                || path.equals("/api/v1/notifications") || path.startsWith("/api/v1/notifications/");
    }
}
