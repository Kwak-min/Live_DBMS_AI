package com.example.monitoring.partc.api;

import java.time.Instant;

public record PartCQueryWindow(Instant start, Instant end) {
}
