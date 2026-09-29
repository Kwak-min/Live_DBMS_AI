package com.example.monitoring.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Items for this page; empty when the page has no results") List<T> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Zero-based page number") int page,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Requested page size") int size,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Total matching rows") long totalElements,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Zero when there are no matching rows") int totalPages
) {
    public static <S, T> PageResponse<T> from(Page<S> source, Function<S, T> mapper) {
        return new PageResponse<>(source.getContent().stream().map(mapper).toList(),
                source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
    }
}
