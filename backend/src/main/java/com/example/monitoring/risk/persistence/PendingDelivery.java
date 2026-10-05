package com.example.monitoring.risk.persistence;

import java.util.UUID;

public record PendingDelivery(long id, UUID incidentId) {
}
