package com.example.monitoring.common.stream;

@FunctionalInterface
public interface StreamRecordHandler {

    default void verifyPrerequisite() {
    }

    void handle(StreamRecord record);
}
