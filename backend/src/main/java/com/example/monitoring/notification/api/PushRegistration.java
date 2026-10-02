package com.example.monitoring.notification.api;

public record PushRegistration(boolean created, PushSubscriptionResponse subscription) {
}
