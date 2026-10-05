package com.example.monitoring.notification.slack;

public final class SlackPayload {
    private final String text;
    private final byte[] body;

    SlackPayload(String text, byte[] body) {
        this.text = text;
        this.body = body.clone();
    }

    public String text() {
        return text;
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "SlackPayload[bodyLength=" + body.length + ']';
    }
}
