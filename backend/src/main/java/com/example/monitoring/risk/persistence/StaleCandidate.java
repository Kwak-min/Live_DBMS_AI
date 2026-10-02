package com.example.monitoring.risk.persistence;

import java.time.Instant;

public record StaleCandidate(long databaseConfigId, Instant dueAt) {
}
