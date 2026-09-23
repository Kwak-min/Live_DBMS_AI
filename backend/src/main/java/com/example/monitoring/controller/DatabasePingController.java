package com.example.monitoring.controller;

import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.service.DatabaseHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/databases")
@RequiredArgsConstructor
@Tag(name = "Database Health API", description = "Endpoints for testing remote MariaDB connectivity and version check")
public class DatabasePingController {

    private final DatabaseHealthService databaseHealthService;

    @GetMapping("/{id}/ping")
    @Operation(summary = "Ping Target Database", description = "Executes lightweight SELECT 1 ping and SELECT VERSION() against target MariaDB")
    public ResponseEntity<DbPingResponseDto> pingDatabase(@PathVariable("id") Long id) {
        return databaseHealthService.pingDatabase(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
