package com.example.monitoring.partc.api;

public record PartCPage(int page, int size) {

    public long offset() {
        return (long) page * size;
    }
}
