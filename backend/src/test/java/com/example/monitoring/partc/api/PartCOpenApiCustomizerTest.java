package com.example.monitoring.partc.api;

import com.example.monitoring.common.config.OpenApiConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class PartCOpenApiCustomizerTest {

    @Test
    void partCResponsesUseCommonJsonContractWithoutChangingOtherPaths() {
        OpenAPI api = new OpenAPI().components(new Components()).paths(new Paths());
        api.path("/api/v1/databases/{id}/status", new PathItem().get(operation("200", "404")));
        api.path("/api/v1/databases/{id}/risk-policy", new PathItem().get(operation("200", "409")));
        api.path("/api/v1/incidents", new PathItem().get(operation("200", "400")));
        api.path("/api/v1/notifications/webhooks/{id}",
                new PathItem().delete(operation("204", "404")));
        api.path("/api/v1/auth/me", new PathItem().get(operation("200", "401")));
        api.path("/unrelated", new PathItem().get(operation("200", "400")));

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(OpenApiConfig.class)) {
            context.getBean(OpenApiCustomizer.class).customise(api);
        }

        assertThat(content(api, "/api/v1/incidents", "get", "200").keySet())
                .containsExactly("application/json");
        assertThat(content(api, "/api/v1/databases/{id}/status", "get", "200").keySet())
                .containsExactly("application/json");
        assertThat(content(api, "/api/v1/databases/{id}/risk-policy", "get", "200").keySet())
                .containsExactly("application/json");
        assertThat(errorRef(api, "/api/v1/databases/{id}/status", "get", "404"))
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(errorRef(api, "/api/v1/incidents", "get", "400"))
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(response(api, "/api/v1/notifications/webhooks/{id}", "delete", "204").getContent())
                .isNull();
        assertThat(errorRef(api, "/api/v1/notifications/webhooks/{id}", "delete", "404"))
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(errorRef(api, "/api/v1/auth/me", "get", "401"))
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(content(api, "/unrelated", "get", "400").keySet()).containsExactly("*/*");
    }

    private Operation operation(String success, String failure) {
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse(success, responseWithWildcardSchema());
        responses.addApiResponse(failure, responseWithWildcardSchema());
        return new Operation().responses(responses);
    }

    private ApiResponse responseWithWildcardSchema() {
        return new ApiResponse().content(new Content().addMediaType(
                "*/*", new MediaType().schema(new StringSchema())));
    }

    private ApiResponse response(OpenAPI api, String path, String method, String code) {
        Operation operation = switch (method) {
            case "get" -> api.getPaths().get(path).getGet();
            case "delete" -> api.getPaths().get(path).getDelete();
            default -> throw new IllegalArgumentException("Unsupported test method: " + method);
        };
        return operation.getResponses().get(code);
    }

    private Content content(OpenAPI api, String path, String method, String code) {
        return response(api, path, method, code).getContent();
    }

    private String errorRef(OpenAPI api, String path, String method, String code) {
        return content(api, path, method, code).get("application/json").getSchema().get$ref();
    }
}
