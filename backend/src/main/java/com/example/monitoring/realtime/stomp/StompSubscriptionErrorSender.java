package com.example.monitoring.realtime.stomp;

interface StompSubscriptionErrorSender {
    void send(String sessionId, Long databaseConfigId, String subscriptionId, StompFailure failure);
}
